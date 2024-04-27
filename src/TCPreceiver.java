import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;


public class TCPreceiver {
    
    protected int portNum;
    protected int mtu;
    protected int sws;
    protected String fileName;

    private InetAddress remoteIP; //init when first SYN packet is received
    private int remotePort;       //init when first SYN packet is received
    private DatagramSocket socket;

    private int seqNum; //Double check, will change throughout
    private int ackNum; //Double check if needed

    /** HashMap to keep track of received segments, i.e data buffer*/
    private ConcurrentHashMap<Integer, byte[]> dataBuffer;

    /** Statistics of data transfer*/
    private int AMOUNT_DATA_REC;
    private int NUM_PACKETS_REC;
    private int NUM_PACKETS_DISCARDED_CHECKSUM;
    private int NUM_PACKETS_DISCARDED_OUT_OF_SEQ;

    /**
     * Constructor for TCPreceiver
     */
    public TCPreceiver(int portNum, int mtu, int sws, String fileName) {
        this.portNum = portNum;
        this.mtu = mtu;
        this.sws = sws;
        this.fileName = fileName;

        this.seqNum = 0;
        this.ackNum = 0;
        this.dataBuffer = new ConcurrentHashMap<>();
    }

    /**
     * Runs our TCPreceiver through various phases
     */
    public void run(){

        //Passive open
        try {
            this.socket = new DatagramSocket(this.portNum);
        } catch(SocketException e1) {
            System.out.println("Failed to create socket in TCPreceiver. Exiting");
            e1.printStackTrace();
            return;
        }

        boolean isRunning = true;
        boolean terminationRequested = false;
        while(isRunning) {
            TCP receivePacket = receiveTCP();
            if(receivePacket == null) continue;

            this.NUM_PACKETS_REC++;
            byte flag = (byte)(receivePacket.getLength() & 0x07);

            //Case 1: Syn Packet
            if((flag & TCP.SYN_FLAG) == TCP.SYN_FLAG) {
                this.ackNum = receivePacket.getSequenceNum() + 1;
                TCP synAckPacket = new TCP(this.seqNum, this.ackNum, System.nanoTime(), TCP.SYN_FLAG + TCP.ACK_FLAG, (short)0, null);
                this.sendTCP(synAckPacket);
            }
            //Case 2a: Fin Packet
            else if((flag & TCP.FIN_FLAG) == TCP.FIN_FLAG) {
                this.ackNum = receivePacket.getSequenceNum() + 1;
                this.seqNum = receivePacket.getAcknowledge(); //Update sequence num here only, because receiver never sends data
                
                TCP finAckPacket = new TCP(this.seqNum, this.ackNum, System.nanoTime(), TCP.FIN_FLAG + TCP.ACK_FLAG, (short)0, null);
                this.sendTCP(finAckPacket);
                terminationRequested = true;
                
                //Lazy write
                writeToFile();
            }
            //Case 2b: Ack Packet for termination
            else if(((flag & TCP.ACK_FLAG) == TCP.ACK_FLAG) && terminationRequested) {
                isRunning = false;
            }
            //Case 4: Data Packet
            else if((receivePacket.getLength() >>> 3) > 0) {
     
                this.ackNum = (receivePacket.getSequenceNum() == this.ackNum) ? receivePacket.getSequenceNum() + (receivePacket.getLength() >>> 3) : this.ackNum;
                     
                if(!dataBuffer.containsKey(receivePacket.getSequenceNum())) {
                    dataBuffer.put(receivePacket.getSequenceNum(), receivePacket.getData());
                    this.AMOUNT_DATA_REC += (receivePacket.getLength() >>> 3);
                }

                //Update this.ackNum to account for gaps
                while(dataBuffer.containsKey(this.ackNum)) { this.ackNum += dataBuffer.get(this.ackNum).length; }

                TCP ackPacket = new TCP(this.seqNum, this.ackNum, System.nanoTime(), TCP.ACK_FLAG, (short)0, null);
                this.sendTCP(ackPacket);
            }
        }

        socket.close(); //We should close AFTER receiving the terminating ack from sender

        //Print statistics only when everything goes well
        this.printStats();
    }

    /**
     * Simply sends the desired tcpPacket using DatagramSocket.
     * Correct content is responsibility of caller
     */
    public void sendTCP(TCP tcpPacket) {

        tcpPacket.setAcknowledge(this.ackNum); //Set ack field (will change throughout)

        tcpPacket.setTimeStamp(System.nanoTime()); //Set time field

        byte[] serialized = tcpPacket.serialize(); //Serialize (proper checksum will be added)
        
        //Send
        try {
            DatagramPacket datagramPacket = new DatagramPacket(serialized, serialized.length, 
                                        this.remoteIP, this.remotePort);

            this.socket.send(datagramPacket);

            System.out.println("snd " + (tcpPacket.getTimeStamp() / 1000000000L) + " " + tcpPacket.getFlags() + 
                    tcpPacket.getSequenceNum() + " " + (tcpPacket.getLength() >>> 3) + " " + tcpPacket.getAcknowledge());

        } catch(UnknownHostException e1) {
            System.out.println("Failed to find host in sendTCP() of TCPreceiver");
            e1.printStackTrace();
            System.exit(1);
        } catch(IOException e2) {
            System.out.println("Failed to send packet in sendTCP() of TCPreceiver");
            e2.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Receieves a TCP packet using DatagramSocket
     */
    public TCP receiveTCP() {

        try {
            byte[] data = new byte[this.mtu + TCP.SIZE_OF_HEADER];
            DatagramPacket receivePacket = new DatagramPacket(data, data.length);

            this.socket.receive(receivePacket);

            this.remoteIP = receivePacket.getAddress();
            this.remotePort = receivePacket.getPort();
            
            TCP returnPacket = new TCP();
            returnPacket.deserialize(data, 0, data.length);

            short savedChecksum = returnPacket.getChecksum();
            returnPacket.resetChecksum();
            byte[] tempPacket = returnPacket.serialize();
            returnPacket.deserialize(tempPacket, 0, tempPacket.length);

            if(savedChecksum != returnPacket.getChecksum()) {
                this.NUM_PACKETS_DISCARDED_CHECKSUM++;
                return null;
            }
            
            System.out.println("rcv " + (returnPacket.getTimeStamp() / 1000000000L) + " " + returnPacket.getFlags() + 
                    returnPacket.getSequenceNum() + " " + (returnPacket.getLength() >>> 3) + " " + returnPacket.getAcknowledge());
                        
            return returnPacket;

        } catch (IOException e) {
            System.out.println("IOException occured in receiveTCP()");
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Helper method to write all contents from buffer to file
     */
    public void writeToFile() {

        try {
            File outputFile = new File(this.fileName);
            FileOutputStream outStream = new FileOutputStream(outputFile);

            int byteIndex = 1;
            while(dataBuffer.containsKey(byteIndex)) {
                outStream.write(dataBuffer.get(byteIndex));
                byteIndex += dataBuffer.get(byteIndex).length;
            }
            outStream.close();

        } catch(IOException e) {
            System.out.println("Unable to write to file in TCPreceiver writeToFile()");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Prints statistics after a successful TCP sesssion 
     */
    private void printStats() {
        System.out.print(String.format("Amount of Data received: %d\n" +  
                                        "Number of packets received: %d\n" + 
                                        "Number of out-of-sequence packets discarded: %d\n" +
                                        "Number of packets discarded due to incorrect checksum: %d\n",
                                        this.AMOUNT_DATA_REC, this.NUM_PACKETS_REC, this.NUM_PACKETS_DISCARDED_OUT_OF_SEQ, this.NUM_PACKETS_DISCARDED_CHECKSUM));
    }

    @Override
    public String toString() {
        return String.format("portNum: %d | mtu: %d | sws: %d | filename: %s", portNum, mtu, sws, fileName);
    }
}