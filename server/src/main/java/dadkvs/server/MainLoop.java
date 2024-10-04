package dadkvs.server;

import dadkvs.DadkvsMain;
import java.util.PriorityQueue;
import java.util.Comparator;

public class MainLoop implements Runnable {
	DadkvsServerState server_state;
	private boolean has_work;
	private PriorityQueue<DadkvsMain.CommitRequest> orderedRequests;
	private int expectedSeqNum = 0;

	public MainLoop(DadkvsServerState state) {
		this.server_state = state;
		this.has_work = false;
		this.orderedRequests = new PriorityQueue<>(Comparator.comparingInt(DadkvsMain.CommitRequest::getReqid));
	}

	public void run() {
		while (true)
			this.doWork();
	}

	synchronized public void doWork() {
		while (!has_work && orderedRequests.isEmpty()) {
			try {
				wait();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		has_work = false;

		while (!orderedRequests.isEmpty()) {
			DadkvsMain.CommitRequest request = orderedRequests.poll();
			processRequest(request);
		}
	}

	synchronized public void wakeup() {
		has_work = true;
		notify();
	}

	public void addOrderedRequest(int seqNum, DadkvsMain.CommitRequest request) {
		orderedRequests.offer(
				DadkvsMain.CommitRequest.newBuilder(request)
						.setReqid(seqNum)
						.build());
		wakeup();
	}

	private void processRequest(DadkvsMain.CommitRequest orderedRequest) {
		if (orderedRequest.getReqid() != expectedSeqNum) {

			orderedRequests.offer(orderedRequest);
			return;
		}

		TransactionRecord txRecord = new TransactionRecord(
				orderedRequest.getKey1(), orderedRequest.getVersion1(),
				orderedRequest.getKey2(), orderedRequest.getVersion2(),
				orderedRequest.getWritekey(), orderedRequest.getWriteval(),
				orderedRequest.getReqid());
		boolean result = server_state.store.commit(txRecord);
		System.out
				.println("Processed ordered request with seqNum " + orderedRequest.getReqid() + ", result: " + result);

		expectedSeqNum++;
	}
}