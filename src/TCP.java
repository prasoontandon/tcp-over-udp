import java.nio.ByteBuffer;

/**
 *
 * @author Prasoon Tandon
 * @author John Lee
 */
public class TCP {
    
    public final static byte SYN_FLAG = 0x04;
    public final static byte FIN_FLAG = 0x02;
    public final static byte ACK_FLAG = 0x01;

    public final static int MAX_NUM_RETRANS = 16;
    public final static int SIZE_OF_HEADER = 24;

    protected int sequenceNum;
    protected int acknowledge;
    protected long timeStamp;
    protected int length; //Includes least 3 sig-bits as FLAGS
    protected short checksum;
    protected byte[] data;

    public TCP(){}

    public TCP(int sn, int ack, long ts, int l, short cs, byte[] data) {
        this.sequenceNum = sn;
        this.acknowledge = ack;
        this.timeStamp = ts;
        this.length = l;
        this.checksum = cs;
        this.data = data;
    } 

    public int getSequenceNum() {
        return this.sequenceNum;
    }
    public TCP setSequenceNum(int num) {
        this.sequenceNum = num;
        return this;
    }

    public int getAcknowledge() {
        return this.acknowledge;
    }
    public TCP setAcknowledge(int ack) {
        this.acknowledge = ack;
        return this;
    }

    public long getTimeStamp() {
        return this.timeStamp;
    }
    public TCP setTimeStamp(long tmstmp) {
        this.timeStamp = tmstmp;
        return this;
    }

    public int getLength() {
        return this.length;
    }
    public TCP setLength(int len) {
        this.length = len;
        return this;
    }

    public byte[] getData() {
        return this.data;
    }

    public short getChecksum() {
        return this.checksum;
    }
    public TCP setChecksum(short checksum) {
        this.checksum = checksum;
        return this;
    }
    public TCP resetChecksum() {
        this.checksum = (short)0;
        return this;
    }

    public String getFlags() {
        String ret = "";
        byte flag = (byte)(this.length & 0x07); 
        
        ret += ((flag & SYN_FLAG) == SYN_FLAG) ? "S " : "- ";
        ret += ((flag & ACK_FLAG) == ACK_FLAG) ? "A " : "- ";
        ret += ((flag & FIN_FLAG) == FIN_FLAG) ? "F " : "- ";
        ret += ((this.length >>> 3) > 0) ? "D " : "- ";

        return ret;

    }

    public static short calcChecksum(byte[] data) {
        int sum = 0;
        int i = 0;
        int val = 0;

        while(i <= data.length - 2){
            val = ((data[i] << 8) & 0xFF00) | ((data[i+1]) & 0xFF);
            sum += val;

            if((sum & 0xFFFF0000) > 0) {
                sum = sum & 0xFFFF;
                sum += 1;
            }

            i+= 2;
        }

        if((data.length % 2) == 1) {
            sum += ((data[data.length - 1] << 8) & 0xFF00);
            if((sum & 0xFFFF0000) > 0) {
                sum = sum & 0xFFFF;
                sum += 1;
            }
        }

        sum = ~sum;
        sum &= 0xFFFF;
        return (short) sum;
    }


    /**
     * Serializes the packet. Will compute and set the following fields if they
     * are set to specific values at the time serialize is called:
     *      -checksum : 0
     */
    public byte[] serialize() {
        int length = (6 << 2) + (this.length >>> 3); //total size of this packet

        byte[] packet = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(packet);

        bb.putInt(this.sequenceNum);
        bb.putInt(this.acknowledge);
        bb.putLong(this.timeStamp);
        bb.putInt(this.length);
        bb.putShort((short)0x00);
        bb.putShort(this.checksum);

        if (this.data != null)
            bb.put(this.data);

        // compute checksum if needed (make sure it is 0 before serialize() is called)
        if (this.checksum == 0) {
            bb.rewind();

            short tempChecksum = calcChecksum(packet);
            bb.putShort(22, tempChecksum);
        }
        return packet;
    }

    public TCP deserialize(byte[] packet, int offset, int length) {
        ByteBuffer bb = ByteBuffer.wrap(packet, offset, length);
        this.sequenceNum = bb.getInt();
        this.acknowledge = bb.getInt();
        this.timeStamp = bb.getLong();
        this.length = bb.getInt();
        bb.getShort(); //gets rid of 0 padding 
        this.checksum = bb.getShort();

        this.data = new byte[this.length >>> 3];
        for(int i = 0; i < data.length; i++) {
            this.data[i] = bb.get();
        }
        
        if(this.data.length == 0) { this.data = null; }
        
        return this;
    }


    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof TCP))
            return false;
        
        TCP other = (TCP) obj;

        boolean fieldsMatching = (sequenceNum == other.sequenceNum) &&
                                (acknowledge == other.acknowledge) &&
                                (timeStamp == other.timeStamp) &&
                                (length == other.length) &&
                                (checksum == other.checksum);

        boolean dataMatching = true;
        if(data == null && other.data == null);
        else {
            for(int i = 0; i < data.length; i++) {
                if(other.data[i] != data[i]){
                    dataMatching = false;
                    break;
                }
            }
        }

        return fieldsMatching && dataMatching;
    }

    @Override
    public String toString() {
        return String.format("SN: %d | A: %d | TS: %d | L: %d | CS: %d | FLGS: S - %d, F - %d, A - %d", 
            sequenceNum, acknowledge, timeStamp, (length>>>3), checksum, ((length & SYN_FLAG)>>>2), ((length & FIN_FLAG)>>>1), (length & ACK_FLAG));
    }

    public String dataToString() {
        String s = "";

        if (data == null) return s;
        
        for(byte b : data)
            s += (char)b;

        return s.trim();
    }
}
