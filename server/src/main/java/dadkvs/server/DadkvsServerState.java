package dadkvs.server;

import dadkvs.DadkvsMain;
import dadkvs.DadkvsMainServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.ArrayList;
import java.util.List;

public class DadkvsServerState {
    // Estados gerais do servidor
    boolean i_am_leader;
    int debug_mode;
    int base_port;
    int my_id;
    int store_size;
    KeyValueStore store;
    MainLoop main_loop;
    Thread main_loop_worker;
    private int sequenceNumber;
    private List<DadkvsMainServiceGrpc.DadkvsMainServiceBlockingStub> serverStubs;

    // Estados para o algoritmo Paxos
    private int highestPromisedProposal = -1;   // Maior número de proposta prometido
    private int lastAcceptedProposal = -1;      // Último número de proposta aceito
    private String lastAcceptedValue = "";      // Último valor aceito (ajustado para inicializar corretamente)
    private String learnedValue = "";           // Valor aprendido (após consenso)

    public DadkvsServerState(int kv_size, int port, int myself) {
        base_port = port;
        my_id = myself;
        i_am_leader = (myself == 0);
        debug_mode = 0;
        store_size = kv_size;
        store = new KeyValueStore(kv_size);
        main_loop = new MainLoop(this);
        main_loop_worker = new Thread(main_loop);
        main_loop_worker.start();
        sequenceNumber = 0;
        initializeServerStubs();
    }

    private void initializeServerStubs() {
        serverStubs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (i != my_id) {
                String host = "localhost";
                int port = base_port + i;
                ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                        .usePlaintext()
                        .build();
                DadkvsMainServiceGrpc.DadkvsMainServiceBlockingStub stub = DadkvsMainServiceGrpc
                        .newBlockingStub(channel);
                serverStubs.add(stub);
            }
        }
    }

    public synchronized int getNextSequenceNumber() {
        return ++sequenceNumber;
    }

    public boolean isLeader() {
        return i_am_leader;
    }

    public void broadcastOrderedRequest(int seqNum, DadkvsMain.CommitRequest request) {
        DadkvsMain.CommitRequest orderedRequest = DadkvsMain.CommitRequest.newBuilder(request)
                .setReqid(seqNum)
                .build();

        for (DadkvsMainServiceGrpc.DadkvsMainServiceBlockingStub stub : serverStubs) {
            try {
                stub.committx(orderedRequest);
            } catch (Exception e) {
                System.err.println("Failed to send ordered request to a server: " + e.getMessage());
            }
        }
    }

    public DadkvsMainServiceGrpc.DadkvsMainServiceBlockingStub getLeaderStub() {
        return serverStubs.get(0);
    }

    // Paxos: Getter e Setter para o maior número de proposta prometido
    public synchronized int getHighestPromisedProposal() {
        return highestPromisedProposal;
    }

    public synchronized void setHighestPromisedProposal(int proposalNumber) {
        this.highestPromisedProposal = proposalNumber;
    }

    // Paxos: Getter e Setter para o último número de proposta aceito
    public synchronized int getLastAcceptedProposal() {
        return lastAcceptedProposal;
    }

    public synchronized void setLastAcceptedProposal(int proposalNumber) {
        this.lastAcceptedProposal = proposalNumber;
    }

    // Paxos: Getter e Setter para o último valor aceito
    public synchronized String getLastAcceptedValue() {
        return lastAcceptedValue;
    }

    public synchronized void setLastAcceptedValue(String value) {
        this.lastAcceptedValue = value;
    }

    // Paxos: Getter e Setter para o valor aprendido
    public synchronized String getLearnedValue() {
        return learnedValue;
    }

    public synchronized void setLearnedValue(String value) {
        this.learnedValue = value;
    }
}
