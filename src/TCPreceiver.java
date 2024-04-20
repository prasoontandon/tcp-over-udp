import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import java.io.FileOutputStream;
import java.io.File;

public class TCPreceiver {
    
    protected int portNum;
    protected int mtu;
    protected int sws;
    protected String fileName;

    private InetAddress remoteIP;
    private int remotePort;
    private DatagramSocket socket;
    private int seqNum; //Double check, will change throughout
    private int ackNum; //Double check if needed; DONT forget to init

    private ConcurrentHashMap<Integer, byte[]> dataBuffer;

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
     * Simply sends the desired tcpPacket using DatagramSocket.
     * Correct content is responsibility of caller
     */
    public void sendTCP(TCP tcpPacket) {

        tcpPacket.setAcknowledge(this.ackNum); //Set ack field

        tcpPacket.setTimeStamp(System.nanoTime()); //Set time field

        byte[] serialized = tcpPacket.serialize(); //Serialize
        
        //Send
        try {
            DatagramPacket datagramPacket = new DatagramPacket(serialized, serialized.length, 
                                        this.remoteIP, this.remotePort);

            this.socket.send(datagramPacket);

            System.out.println("snd " + (tcpPacket.getTimeStamp() / 1000) + " " + tcpPacket.getFlags() + 
                    tcpPacket.getSequenceNum() + " " + (tcpPacket.getLength() >>> 3) + " " + tcpPacket.getAcknowledge());

        } catch(UnknownHostException e1) {
            System.out.println("Failed to find host in sendTCP() of TCPreceiver");
            System.exit(1);
        } catch(IOException e2) {
            System.out.println("Failed to send packet in sendTCP() of TCPreceiver");
            System.exit(1);
        }
    }

    public void run(){

        //Passive open
        try {
            this.socket = new DatagramSocket(this.portNum, InetAddress.getByName("localhost"));
        } catch(SocketException e) {
            System.out.println("Failed to create socket in TCPreceiver. Exiting");
            return;
        } catch(UnknownHostException e1) {
            System.out.println("Failed to create socket in TCPreceiver. Exiting");
            return;
        }

        boolean isRunning = true;
        boolean terminationRequested = false;
        while(isRunning) {
            TCP receivePacket = receiveTCP();
            if(receivePacket == null) continue;

            byte flag = (byte)(receivePacket.getLength() & 0x07);

            //Case 1: Syn Packet
            if((flag & TCP.SYN_FLAG) == TCP.SYN_FLAG) {
                this.ackNum = receivePacket.getSequenceNum() + 1;
                TCP synAckPacket = new TCP(this.seqNum, this.ackNum, System.nanoTime(), TCP.SYN_FLAG + TCP.ACK_FLAG, (short)0, null);
                this.sendTCP(synAckPacket);
            }
            //Case 2-a: Fin Packet
            else if((flag & TCP.FIN_FLAG) == TCP.FIN_FLAG) {
                this.ackNum = receivePacket.getSequenceNum() + 1;
                this.seqNum++; //Update sequence num here only, because receiver never sends data
                TCP finAckPacket = new TCP(this.seqNum, this.ackNum, System.nanoTime(), TCP.FIN_FLAG + TCP.ACK_FLAG, (short)0, null);
                this.sendTCP(finAckPacket);
                terminationRequested = true;
            }
            //Case 2-b: Ack Packet for termination
            else if(((flag & TCP.ACK_FLAG) == TCP.ACK_FLAG) && terminationRequested) {
                isRunning = false;
            }
            //Case 4: Data Packet
            else if((receivePacket.getLength() >>> 3) > 0) {
        // System.out.println("Received data in receiver");
                this.ackNum = (receivePacket.getSequenceNum() == this.ackNum) ? receivePacket.getSequenceNum() + (receivePacket.getLength() >>> 3) : this.ackNum;

                byte[] packetData = (dataBuffer.containsKey(receivePacket.getSequenceNum())) ? dataBuffer.get(receivePacket.getSequenceNum()): receivePacket.getData();
                dataBuffer.put(receivePacket.getSequenceNum(), packetData);

                //NOTE: Don't forget to update this.ackNum to account for gaps
                while(dataBuffer.containsKey(this.ackNum)) {
                    System.out.println("We shouldn't be here in receiver()!");
                    this.ackNum += dataBuffer.get(this.ackNum).length;
                }
// System.out.println("About to send ACK in receiver");
                TCP ackPacket = new TCP(this.seqNum, this.ackNum, System.nanoTime(), TCP.ACK_FLAG, (short)0, null);
                this.sendTCP(ackPacket);
            }
        }

        writeToFile();

        socket.close();
    }

    public TCP receiveTCP() {

        try {
            byte[] data = new byte[this.mtu];
            DatagramPacket receivePacket = new DatagramPacket(data, data.length);
    // System.out.println("REACHED SOCKET RECEIVE");
            this.socket.receive(receivePacket);

// System.out.println("REACHED FIELD INIT");

            this.remoteIP = receivePacket.getAddress();
            this.remotePort = receivePacket.getPort();
            
            TCP returnPacket = new TCP();
    // System.out.println("REACHED DESEARALIZE");

            returnPacket.deserialize(data, 0, data.length);

// System.out.println("returnPacket value in receiver: " + returnPacket);
// System.out.println("returnPacket data value in receiver: " + returnPacket.dataToString());

            System.out.println("rcv " + (returnPacket.getTimeStamp() / 1000) + " " + returnPacket.getFlags() + 
                    returnPacket.getSequenceNum() + " " + (returnPacket.getLength() >>> 3) + " " + returnPacket.getAcknowledge());
            
            // if((returnPacket.getLength() >>> 3) > 0) {
            //     this.ackNum = (returnPacket.getSequenceNum() == this.ackNum) ? returnPacket.getSequenceNum() + (returnPacket.getLength() >>> 3) : this.ackNum;
            // } else {
            //     this.ackNum = returnPacket.getSequenceNum() + 1;
            // }
            
            return returnPacket;

        }catch (IOException e) {
            System.out.println("IOException occured in receiveTCP()");
            return null;
        }
    }

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

        }catch(IOException e) {
            System.out.println("Unable to write to file in TCPreceiver writeToFile()");
            System.exit(1);
        }
    }

    @Override
    public String toString() {
        return String.format("portNum: %d | mtu: %d | sws: %d | filename: %s", portNum, mtu, sws, fileName);
    }
}