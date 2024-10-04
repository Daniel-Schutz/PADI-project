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
		// for debug purposes
		System.out.println("Receiving commit request:" + request);

		int reqid = request.getReqid();
		int key1 = request.getKey1();
		int version1 = request.getVersion1();
		int key2 = request.getKey2();
		int version2 = request.getVersion2();
		int writekey = request.getWritekey();
		int writeval = request.getWriteval();

		// for debug purposes
		System.out.println("reqid " + reqid + " key1 " + key1 + " v1 " + version1 + " k2 " + key2 + " v2 " + version2
				+ " wk " + writekey + " writeval " + writeval);

		if (server_state.isLeader()) {
			this.timestamp++;
			TransactionRecord txrecord = new TransactionRecord(key1, version1, key2, version2, writekey, writeval,
					this.timestamp);
			server_state.broadcastOrderedRequest(this.timestamp, request);
			boolean result = processTransaction(txrecord);

			// for debug purposes
			System.out.println("Result is ready for request with reqid " + reqid);

			DadkvsMain.CommitReply response = DadkvsMain.CommitReply.newBuilder()
					.setReqid(reqid).setAck(result).build();

			responseObserver.onNext(response);
			responseObserver.onCompleted();
		} else {
			forwardToLeader(request, responseObserver);
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