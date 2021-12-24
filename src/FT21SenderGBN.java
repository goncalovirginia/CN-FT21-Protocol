import cnss.simulator.Node;
import ft21.*;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class FT21SenderGBN extends FT21AbstractSenderApplication {

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
	
	private Map<Integer, Integer> timeouts;
	
	private boolean canSend;

	private State state;
	
	private int windowStartSeqN;

	public FT21SenderGBN() {
		super(true, "FT21SenderGBN");
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
		timeouts = new HashMap<>();
		
		return 1;
	}

	@Override
	public void on_clock_tick(int now) {
		checkTimeout(now);
		
		if (nextPacketSeqN == windowStartSeqN + windowSize || nextPacketSeqN > lastPacketSeqN) {
			canSend = false;
		}
		
		if (canSend) {
			self.set_timeout(TIMEOUT);
			timeouts.put(nextPacketSeqN, now + TIMEOUT);
			sendNextPacket(now);
		}
	}
	
	public void checkTimeout(int now) {
		if (!timeouts.containsValue(now)) {
			return;
		}
		
		nextPacketSeqN = windowStartSeqN;
		canSend = true;
		tallyTimeout(TIMEOUT);
	}

	private void sendNextPacket(int now) {
		packetsSendTime.put(nextPacketSeqN, now);
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
		if (timeouts.containsValue(ack.cSeqN) && timeouts.get(ack.cSeqN) > ack.timeStamp) {
			tallyTimeout(now - ack.timeStamp);
			timeouts.remove(ack.cSeqN);
		}
		
		switch (state) {
			case BEGINNING:
				state = State.UPLOADING;
				nextPacketSeqN++;
				break;
			case UPLOADING:
				if (ack.cSeqN < windowStartSeqN && ack.timeStamp < packetsSendTime.get(ack.cSeqN + 1)) {
					return;
				}
				
				if (ack.cSeqN < windowStartSeqN) {
					nextPacketSeqN = windowStartSeqN;
				}
				else {
					windowStartSeqN = ack.cSeqN + 1;
				}
				
				if (windowStartSeqN > lastPacketSeqN) {
					state = State.FINISHING;
					sendNextPacket(now);
				}
				
				break;
			case FINISHING:
				super.log(now, "All Done. Transfer complete...");
				super.printReport(now);
				state = State.FINISHED;
				break;
			case FINISHED:
				break;
		}
		
		if (state != State.FINISHED) {
			canSend = true;
			tallyRTT(now - packetsSendTime.get(ack.cSeqN));
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
