import cnss.simulator.Node;
import ft21.*;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FT21SenderSR extends FT21AbstractSenderApplication {

	private static final int TIMEOUT = 1000;

	static int RECEIVER = 1;

	enum State {
		BEGINNING, UPLOADING, FINISHING, FINISHED
	}

	private File file;
	private RandomAccessFile raf;
	private int blockSize;
	private int windowSize;
	private int nextPacketSeqN, lastPacketSeqN;
	
	private Map<Integer, Integer> packetsSendTime;
	
	private Map<Integer, Integer> timeoutsSeqNAsKey;
	private Map<Integer, Integer> timeoutsTimeAsKey;
	
	private Set<Integer> ackedPackets;
	
	private boolean canSend;

	private State state;
	
	private int windowStartSeqN;

	public FT21SenderSR() {
		super(true, "FT21SenderSR");
	}

	@Override
	public int initialise(int now, int node_id, Node nodeObj, String[] args) {
		super.initialise(now, node_id, nodeObj, args);

		raf = null;
		
		file = new File(args[0]);
		blockSize = Integer.parseInt(args[1]);
		windowSize = Integer.parseInt(args[2]);

		state = State.BEGINNING;
		nextPacketSeqN = 0;
		lastPacketSeqN = (int) Math.ceil(file.length() / (double) blockSize);
		canSend = true;
		windowStartSeqN = 1;
		
		packetsSendTime = new HashMap<>();
		timeoutsSeqNAsKey = new HashMap<>();
		timeoutsTimeAsKey = new HashMap<>();
		ackedPackets = new HashSet<>();
		
		return 1;
	}

	@Override
	public void on_clock_tick(int now) {
		if (checkTimeout(now)) {
			return;
		}
		
		if (nextPacketSeqN == windowStartSeqN + windowSize || nextPacketSeqN > lastPacketSeqN) {
			canSend = false;
		}
		
		if (canSend) {
			sendNextPacket(now);
		}
	}
	
	private boolean checkTimeout(int now) {
		/*
		System.out.println("active timeouts:");
		System.out.println(timeoutsSeqNAsKey.keySet());
		System.out.println(timeoutsSeqNAsKey.values());
		System.out.println(timeoutsTimeAsKey.keySet());*/
		if (!timeoutsSeqNAsKey.containsValue(now)) {
			return false;
		}
		
		//System.out.println("timeout at " + now + " for seqN " + timeoutsTimeAsKey.get(now));
		
		int temp = nextPacketSeqN;
		nextPacketSeqN = timeoutsTimeAsKey.get(now);
		sendNextPacket(now);
		nextPacketSeqN = temp;
		
		tallyTimeout(TIMEOUT);
		
		return true;
	}

	private void sendNextPacket(int now) {
		packetsSendTime.put(nextPacketSeqN, now);
		timeoutsSeqNAsKey.put(nextPacketSeqN, now + TIMEOUT);
		timeoutsTimeAsKey.put(now + TIMEOUT, nextPacketSeqN);
		
		switch (state) {
			case BEGINNING:
				super.sendPacket(now, RECEIVER, new FT21_UploadPacket(file.getName()));
				break;
			case UPLOADING:
				super.sendPacket(now, RECEIVER, readDataPacket(file, nextPacketSeqN++, now));
				break;
			case FINISHING:
				super.sendPacket(now, RECEIVER, new FT21_FinPacket(nextPacketSeqN));
				break;
			case FINISHED:
				break;
		}
	}

	@Override
	public void on_receive_ack(int now, int client, FT21_AckPacket ack) {
		if (timeoutsSeqNAsKey.containsKey(ack.cSeqN) && timeoutsSeqNAsKey.get(ack.cSeqN) > ack.timeStamp) {
			tallyTimeout(now - ack.timeStamp);
			timeoutsTimeAsKey.remove(timeoutsSeqNAsKey.remove(ack.cSeqN));
		}
		
		switch (state) {
			case BEGINNING:
				state = State.UPLOADING;
				nextPacketSeqN++;
				break;
			case UPLOADING:
				if (ack.cSeqN == 0) {
					return;
				}
				
				if (ack.cSeqN < windowStartSeqN) {
					if (ack.timeStamp < packetsSendTime.get(ack.cSeqN + 1)) {
						break;
					}
					int temp = nextPacketSeqN;
					nextPacketSeqN = ack.cSeqN + 1;
					sendNextPacket(now);
					nextPacketSeqN = temp;
					break;
				}
				
				ackedPackets.add(ack.cSeqN);
				
				while (windowStartSeqN <= ack.cSeqN) {
					timeoutsTimeAsKey.remove(timeoutsSeqNAsKey.remove(windowStartSeqN));
					windowStartSeqN++;
				}
				
				if (windowStartSeqN > lastPacketSeqN) {
					state = State.FINISHING;
					sendNextPacket(now);
				}
				
				tallyRTT(now - packetsSendTime.get(ack.cSeqN));
				
				break;
			case FINISHING:
				if (ack.cSeqN <= lastPacketSeqN) {
					break;
				}
				
				super.log(now, "All Done. Transfer complete...");
				super.printReport(now);
				state = State.FINISHED;
				break;
			case FINISHED:
				break;
		}
		
		if (state != State.FINISHED) {
			canSend = true;
		}
	}

	private FT21_DataPacket readDataPacket(File file, int seqN, int now) {
		try {
			if (raf == null) {
				raf = new RandomAccessFile(file, "r");
			}

			byte[] optionalData = ByteBuffer.allocate(4).putInt(now).array();
			
			raf.seek((long) blockSize * (seqN - 1));
			byte[] data = new byte[blockSize];
			int nbytes = raf.read(data);
			
			return new FT21_DataPacket(seqN, (byte) optionalData.length, optionalData, data, nbytes);
		} catch (Exception x) {
			throw new Error("Fatal Error: " + x.getMessage());
		}
	}
}
