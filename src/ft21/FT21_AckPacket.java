package ft21;

import java.nio.ByteBuffer;

public class FT21_AckPacket extends FT21Packet {
	public final int cSeqN;
	public final boolean outsideWindow;
	public final int timeStamp;
	
	FT21_AckPacket(byte[] bytes) {
		super(bytes);
		int seqN = super.getInt();
		this.cSeqN = Math.abs(seqN);
		this.outsideWindow = seqN < 0;
		
		if (bytes.length > 5) {
			timeStamp = super.getInt();
		}
		else {
			timeStamp = -1;
		}
	}

	public String toString() {
		return String.format("ACK<%d>", cSeqN);
	}
	
}