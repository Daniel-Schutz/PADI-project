package dadkvs.server;

import dadkvs.DadkvsMain;
import dadkvs.DadkvsMainServiceGrpc;
import io.grpc.stub.StreamObserver;

public class DadkvsMainServiceImpl extends DadkvsMainServiceGrpc.DadkvsMainServiceImplBase {

	DadkvsServerState server_state;
	int timestamp;

	public DadkvsMainServiceImpl(DadkvsServerState state) {
		this.server_state = state;
		this.timestamp = 0;
	}

	@Override
	public void read(DadkvsMain.ReadRequest request, StreamObserver<DadkvsMain.ReadReply> responseObserver) {
		// for debug purposes
		System.out.println("Receiving read request:" + request);

		int reqid = request.getReqid();
		int key = request.getKey();
		VersionedValue vv = this.server_state.store.read(key);

		DadkvsMain.ReadReply response = DadkvsMain.ReadReply.newBuilder()
				.setReqid(reqid).setValue(vv.getValue()).setTimestamp(vv.getVersion()).build();

		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}

    @Override
    public void committx(DadkvsMain.CommitRequest request, StreamObserver<DadkvsMain.CommitReply> responseObserver) {
        System.out.println("Receiving commit request:" + request);
    
        int reqid = request.getReqid();
        int key1 = request.getKey1();
        int version1 = request.getVersion1();
        int key2 = request.getKey2();
        int version2 = request.getVersion2();
        int writekey = request.getWritekey();
        int writeval = request.getWriteval();
    
        System.out.println("reqid " + reqid + " key1 " + key1 + " v1 " + version1 + " k2 " + key2 + " v2 " + version2
                + " wk " + writekey + " writeval " + writeval);
    
        if (server_state.isLeader()) {
            this.timestamp++;
    
            TransactionRecord txrecord = new TransactionRecord(key1, version1, key2, version2, writekey, writeval,
                    this.timestamp);
            
            // Inicia uma rodada do Paxos para garantir consenso sobre a ordem da transação
            int sequenceNumber = server_state.getNextSequenceNumber(); // Proposta do próximo número de sequência
            startPaxosForRequest(sequenceNumber, request, responseObserver);
            
        } else {
            forwardToLeader(request, responseObserver);
        }
    }
    
    private void startPaxosForRequest(int seqNum, DadkvsMain.CommitRequest request,
                                      StreamObserver<DadkvsMain.CommitReply> responseObserver) {
        // Iniciar o Paxos (Fase 1 e Fase 2) para garantir a ordem
        DadkvsPaxos.PhaseOneRequest phaseOneRequest = DadkvsPaxos.PhaseOneRequest.newBuilder()
            .setProposalNumber(seqNum)
            .setCommitRequest(request)
            .build();
    
        // Difundir a fase 1 do Paxos
        int acceptCount = 0;
        int totalServers = server_state.getServerStubs().size();
        
        for (DadkvsMainServiceGrpc.DadkvsMainServiceBlockingStub stub : server_state.getServerStubs()) {
            try {
                DadkvsPaxos.PhaseOneReply reply = stub.phaseone(phaseOneRequest);
                // Checar respostas e continuar para a fase 2 do Paxos
                if (reply.getSuccess()) {
                    acceptCount++;
                    if (acceptCount > totalServers / 2) {
                        // A fase 1 foi aceita por uma maioria, proceder com fase 2
                        startPhaseTwo(seqNum, request, responseObserver);
                        return;
                    }
                }
            } catch (Exception e) {
                System.err.println("Falha ao enviar PhaseOneRequest: " + e.getMessage());
            }
        }
    
        if (acceptCount <= totalServers / 2) {
            System.err.println("Falha ao obter maioria na Fase 1 do Paxos.");
            // Responder com falha no consenso
            DadkvsMain.CommitReply commitReply = DadkvsMain.CommitReply.newBuilder()
                .setReqid(request.getReqid())
                .setAck(false)
                .build();
            responseObserver.onNext(commitReply);
            responseObserver.onCompleted();
        }
    }
    
    private void startPhaseTwo(int seqNum, DadkvsMain.CommitRequest request,
                               StreamObserver<DadkvsMain.CommitReply> responseObserver) {
        // Iniciar a fase 2 do Paxos (confirmação)
        DadkvsPaxos.PhaseTwoRequest phaseTwoRequest = DadkvsPaxos.PhaseTwoRequest.newBuilder()
            .setProposalNumber(seqNum)
            .setCommitRequest(request)
            .build();
    
        int successCount = 0;
        int totalServers = server_state.getServerStubs().size();
    
        for (DadkvsMainServiceGrpc.DadkvsMainServiceBlockingStub stub : server_state.getServerStubs()) {
            try {
                DadkvsPaxos.PhaseTwoReply reply = stub.phasetwo(phaseTwoRequest);
                if (reply.getSuccess()) {
                    successCount++;
                    if (successCount > totalServers / 2) {
                        // Paxos alcançou consenso, processar transação
                        boolean result = processTransaction(new TransactionRecord(
                            request.getKey1(), request.getVersion1(),
                            request.getKey2(), request.getVersion2(),
                            request.getWritekey(), request.getWriteval(), this.timestamp
                        ));
    
                        DadkvsMain.CommitReply commitReply = DadkvsMain.CommitReply.newBuilder()
                            .setReqid(request.getReqid())
                            .setAck(result)
                            .build();
                        responseObserver.onNext(commitReply);
                        responseObserver.onCompleted();
                        return;
                    }
                }
            } catch (Exception e) {
                System.err.println("Falha ao enviar PhaseTwoRequest: " + e.getMessage());
            }
        }
    
        if (successCount <= totalServers / 2) {
            System.err.println("Falha ao obter maioria na Fase 2 do Paxos.");
            // Responder com falha no consenso
            DadkvsMain.CommitReply commitReply = DadkvsMain.CommitReply.newBuilder()
                .setReqid(request.getReqid())
                .setAck(false)
                .build();
            responseObserver.onNext(commitReply);
            responseObserver.onCompleted();
        }
    }
    
	private boolean processTransaction(TransactionRecord txrecord) {
		return this.server_state.store.commit(txrecord);
	}

	private void forwardToLeader(DadkvsMain.CommitRequest request,
			StreamObserver<DadkvsMain.CommitReply> responseObserver) {
		try {
			DadkvsMain.CommitReply reply = server_state.getLeaderStub().committx(request);
			responseObserver.onNext(reply);
			responseObserver.onCompleted();
		} catch (Exception e) {
			System.err.println("Failed to forward request to leader: " + e.getMessage());
			responseObserver.onError(e);
		}
	}
}