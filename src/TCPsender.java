import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class TCPsender {
    
    protected int portNum;
    protected int remoteIP;
    protected int remotePort;
    protected String fileName;
    protected int mtu;
    protected int sws;

    private DatagramSocket socket;
    private InetAddress address; //Can likely remove!
    

    private int seqNum; //Double check, will change throughout
    private int ackNum; //Double check if needed; DONT forget to init

    private boolean completed; //Keeps track of completion status of our whole process
    private long TIME_OUT; //Keeps track of timeout, which will vary throughout the process

    private int numSegments;
    
    private ArrayList<TCP> allPackets;
    private ArrayList<TCP> window;
    private ConcurrentHashMap<Integer, Integer> numRetransMap; 
    
    public TCPsender(int portNum, int remoteIP, int remotePort, String fileName, int mtu, int sws) {
        this.portNum = portNum;
        this.remoteIP = remoteIP;
        this.remotePort = remotePort;
        this.fileName = fileName;
        this.mtu = mtu;
        this.sws = sws;

        init();
    }

    /**
     * Initialize the important data structures that will be used for bookeeping
     * and read all the file contents to create all possible TCP packets 
     */
    private void init() {

        try {
            //Get all the file contents as bytes
            byte[] fileAsBytes = null;
            File file = new File(this.fileName);
            fileAsBytes = Files.readAllBytes(file.toPath());

            this.numSegments = (int)Math.ceil(((double)fileAsBytes.length)/(this.mtu - TCP.SIZE_OF_HEADER));
            this.completed = false;
            this.seqNum = 0;
            this.ackNum = 0;
            this.TIME_OUT = 5; //Per the instructions

            //Init all data structures
            this.allPackets = new ArrayList<>(this.numSegments);
            this.window = new ArrayList<>(this.sws);
            this.numRetransMap = new ConcurrentHashMap<>();

            //Create all TCP packets
            int finalSegmentSize = fileAsBytes.length - (this.mtu - TCP.SIZE_OF_HEADER)*(this.numSegments - 1);
            for(int s = 0; s < numSegments; s++) {
                byte[] segment;
                

                if(s == numSegments - 1)
                    segment = new byte[finalSegmentSize];
                else
                    segment = new byte[this.mtu - TCP.SIZE_OF_HEADER];

                for(int b = 0; b < segment.length; b++)
                    segment[b] = fileAsBytes[(s*(this.mtu - TCP.SIZE_OF_HEADER)) + b];
                
                int sn = s*(this.mtu - TCP.SIZE_OF_HEADER) + 1; //+1 for 0th segment after ACK, assuming sn_init = 0
                int ack = -1; //BEFORE SENDING UPDATE!
                int len = (segment.length << 3) + TCP.ACK_FLAG;
                TCP packet = new TCP(sn, ack, System.nanoTime(), len, (short)0, segment);
                this.allPackets.add(packet);
                
                //System.out.println(packet.dataToString());
            }
        } catch(IOException e) {
            System.out.println("Unable to init TCPsender in init()");
            System.exit(1);
        }
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
                                        InetAddress.getByName(""+this.remoteIP), this.remotePort);

            this.socket.send(datagramPacket);

            System.out.println("snd " + (tcpPacket.getTimeStamp() / 1000000000) + " " + tcpPacket.getFlags() + 
                    tcpPacket.getSequenceNum() + " " + (tcpPacket.getLength() >>> 3) + " " + tcpPacket.getAcknowledge());

        } catch(UnknownHostException e1) {
            System.out.println("Failed to find host in sendTCP() of TCPsender");
            System.exit(1);
        } catch(IOException e2) {
            System.out.println("Failed to send packet in sendTCP() of TCPsender");
            System.exit(1);
        }
    }

    //TODO
    public void run(){
        
        //Establish connection (3-way handshake)
        if(!this.establishConnection()) return;

        //Begin transmitting data
        this.transferData();

        //Terminate connection
        if(!this.terminateConnection()) return;
    }

    boolean connectionEstablished = false;
    int numRetrans = 0;
    
    public boolean establishConnection() {

        try {
            this.socket = new DatagramSocket(this.portNum, InetAddress.getByName("localhost"));
        } catch(SocketException e) {
            System.out.println("Failed to create socket in TCPsender. Exiting");
            return false;
        } catch(UnknownHostException e1) {
            System.out.println("Failed to create socket in TCPsender. Exiting");
            return false;
        }

        Thread listenThread = new Thread(new Runnable() {

            @Override
            public void run() {
                while(!connectionEstablished && numRetrans < TCP.MAX_NUM_RETRANS) {
                    TCP recPacket = receiveTCP();
                    if((recPacket == null) || (((recPacket.getLength() & TCP.SYN_FLAG) != TCP.SYN_FLAG) && 
                                            ((recPacket.getLength() & TCP.ACK_FLAG) != TCP.ACK_FLAG))) continue;


                    TCP ackPacket = new TCP(recPacket.getAcknowledge(), recPacket.getSequenceNum() + 1, System.nanoTime(), (int)TCP.ACK_FLAG, (short)0, null);
                    connectionEstablished = true;
                    sendTCP(ackPacket);
                }
            }
        });

        listenThread.start();

        while(!connectionEstablished && numRetrans < TCP.MAX_NUM_RETRANS) {
            TCP synPacket = new TCP(this.seqNum, this.ackNum, System.nanoTime(), (int)TCP.SYN_FLAG, (short)0, null);
            this.sendTCP(synPacket);
            numRetrans++;
            try{ Thread.sleep(this.TIME_OUT); } catch(InterruptedException e) { continue; }
        }

        numRetrans = TCP.MAX_NUM_RETRANS; //This line prevents the listenThread from continuously running when socket is closed immediately
        return connectionEstablished;
    }

    public TCP receiveTCP() {

        try {
            byte[] data = new byte[this.mtu];
            DatagramPacket receivePacket = new DatagramPacket(data, data.length);
            this.socket.receive(receivePacket);
            
            TCP returnPacket = new TCP();
            returnPacket.deserialize(data, 0, data.length);

            System.out.println("rcv " + (returnPacket.getTimeStamp() / 1000000000) + " " + returnPacket.getFlags() + 
                    returnPacket.getSequenceNum() + " " + (returnPacket.getLength() >>> 3) + " " + returnPacket.getAcknowledge());
            
         
            this.ackNum = returnPacket.getSequenceNum() + 1;

            this.seqNum = returnPacket.getAcknowledge();
            
            return returnPacket;

        }catch (IOException e) {
            System.out.println("IOException occured in receiveTCP()");
            return null;
        }
    }

    public boolean transferData() {
        return true;
    }

    /**
     * Helper method to send data packets.
     * Makes sure all content in packets is correct
     * all important fields are updated 
     * 
     * and then calls sendTCP()
     */
    private boolean sendDataPackets() {
        return true;
    }

    boolean connectionTerminated = false;

    public boolean terminateConnection() {

        this.numRetrans = 0;

        Thread listenThread = new Thread(new Runnable() {

            @Override
            public void run() {
                while(!connectionTerminated && numRetrans < TCP.MAX_NUM_RETRANS) {
                    TCP recPacket = receiveTCP();
                    if((recPacket == null) || (((recPacket.getLength() & TCP.FIN_FLAG) != TCP.FIN_FLAG) && 
                                            ((recPacket.getLength() & TCP.ACK_FLAG) != TCP.ACK_FLAG))) continue;


                    TCP ackPacket = new TCP(seqNum, ackNum, System.nanoTime(), (int)TCP.ACK_FLAG, (short)0, null);
                    connectionTerminated = true;
                    sendTCP(ackPacket);
                }
            }
        });

        listenThread.start();

        while(!connectionTerminated && numRetrans < TCP.MAX_NUM_RETRANS) {
            TCP finPacket = new TCP(this.seqNum, this.ackNum, System.nanoTime(), (int)TCP.FIN_FLAG, (short)0, null);
            this.sendTCP(finPacket);
            numRetrans++;
            try{ Thread.sleep(this.TIME_OUT); } catch(InterruptedException e) { continue; }
        }

        numRetrans = TCP.MAX_NUM_RETRANS; //This line prevents the listenThread from continuously running when socket is closed immediately
        this.socket.close();
        return connectionTerminated;
    }


    /** Global variables placed here for convenience*/
    private long ERTT;
    private long EDEV;
    private long SRTT;
    private long SDEV;

    /**
     * Calculates timeout using a simple exponentially weighted average algorithm.
     * This method should be called whenever 
     */
    private void calcTimeout(TCP tcpPacket) {
        double a = 0.875;
        double b = 0.75;
        
        if(tcpPacket.getSequenceNum() == 0) {
            ERTT = System.nanoTime() - tcpPacket.getTimeStamp();
            EDEV = 0;
            this.TIME_OUT = 2 * ERTT;
        } else {
            SRTT = System.nanoTime() - tcpPacket.getTimeStamp();
            SDEV = Math.abs(SRTT - ERTT);
            ERTT = (long)(a*ERTT + (1-a)*SRTT);
            EDEV = (long)(b*EDEV + (1-b)*SDEV);
            this.TIME_OUT = ERTT + 4*EDEV;
        }
    }

    @Override
    public String toString() {
        return String.format("portNum: %d | remoteIP: %d | remotePort: %d | filename: %s | mtu: %d | sws: %d", 
                            portNum, remoteIP, remotePort, fileName, mtu, sws);
    }
}