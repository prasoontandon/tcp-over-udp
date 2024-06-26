import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;


public class TCPsender {
    
    protected int portNum;
    protected String remoteIP;
    protected int remotePort;
    protected String fileName;
    protected int mtu;
    protected int sws;

    private DatagramSocket socket;
    
    private int seqNum; //Double check, will change throughout
    private int ackNum; //Double check if needed

    private volatile boolean completed; //Keeps track of completion status of our whole process
    private long TIME_OUT; //Keeps track of timeout (in nanoseconds), which will vary throughout the process
  
    private ArrayList<TCP> allPackets;
    private volatile int swL; //Sliding window pointer (left)
    private volatile int swR; //Sliding window pointer (right)

    /** HashMaps to keep track of important values relating to segments*/
    private ConcurrentHashMap<Integer, Integer> numAcksMap;
    private ConcurrentHashMap<Integer, Integer> numRetransMap;
    private ConcurrentHashMap<Integer, Long> timeoutMap; 
    
    /** Statistics of data transfer*/
    private int AMOUNT_DATA_TRANS;
    private int NUM_PACKETS_SENT;
    private int NUM_RETRANS;
    private int NUM_DUPLICATE_ACKS;

    /**
     * Constructor for TCPsender
     */
    public TCPsender(int portNum, String remoteIP, int remotePort, String fileName, int mtu, int sws) {
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

            int numSegments = (int)Math.ceil(((double)fileAsBytes.length)/(this.mtu));
            this.completed = false;
            this.seqNum = 0;
            this.ackNum = 0;
            this.TIME_OUT = (long)5e+9; //Per the instructions

            //Init all data structures
            this.allPackets = new ArrayList<>(numSegments);
            this.swL = 0;
            this.swR = 0; 

            this.numAcksMap = new ConcurrentHashMap<>();
            this.numRetransMap = new ConcurrentHashMap<>();
            this.timeoutMap = new ConcurrentHashMap<>();

            //Create all TCP packets
            int finalSegmentSize = fileAsBytes.length - (this.mtu)*(numSegments - 1);
            for(int s = 0; s < numSegments; s++) {
                byte[] segment;
                
                if(s == numSegments - 1)
                    segment = new byte[finalSegmentSize];
                else
                    segment = new byte[this.mtu];

                for(int b = 0; b < segment.length; b++)
                    segment[b] = fileAsBytes[(s*(this.mtu)) + b];
                
                int sn = s*(this.mtu) + 1; //+1 for 0th segment after ACK, assuming init sequenceNumber is 0
                int ack = -1;
                int len = (segment.length << 3) + TCP.ACK_FLAG;
                TCP packet = new TCP(sn, ack, System.nanoTime(), len, (short)0, segment);
                this.allPackets.add(packet);
            }
        } catch(IOException e) {
            System.out.println("Unable to init TCPsender in init()");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Runs our TCPsender through various phases
     */
    public void run(){
        
        //Establish connection (3-way handshake)
        if(!this.establishConnection()) return;

        //Begin transmitting data
        this.transferData();

        //Terminate connection
        if(!this.terminateConnection()) return;

        //Print statistics only when everything goes well
        try{ Thread.sleep((long)(500)); } catch(InterruptedException e) {}
        this.printStats();
    }

    boolean connectionEstablished = false; //Placed here so it is visible in threads below
    int numRetrans = 0;  
    /**
     * Establish connection with the receiver using 3-way handshake
     */
    public boolean establishConnection() {

        try {
            this.socket = new DatagramSocket(this.portNum);
        } catch(SocketException e1) {
            System.out.println("Failed to create socket in TCPsender. Exiting");
            e1.printStackTrace();
            return false;
        }

        Thread listenThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(!connectionEstablished && numRetrans < TCP.MAX_NUM_RETRANS) {
                    TCP recPacket = receiveTCP();
                    if((recPacket == null) || (((recPacket.getLength() & TCP.SYN_FLAG) != TCP.SYN_FLAG) && 
                                            ((recPacket.getLength() & TCP.ACK_FLAG) != TCP.ACK_FLAG))) continue;

                    seqNum = recPacket.getAcknowledge(); //Update sequence number because syn counts as "1 byte"
                    TCP ackPacket = new TCP(seqNum, ackNum, System.nanoTime(), (int)TCP.ACK_FLAG, (short)0, null);
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
            try{ Thread.sleep((long)(this.TIME_OUT/1e+6)); } catch(InterruptedException e) { continue; }
        }

        if(numRetrans >= TCP.MAX_NUM_RETRANS) { this.socket.close(); return connectionEstablished; }

        this.NUM_RETRANS += numRetrans - 1;

        numRetrans = TCP.MAX_NUM_RETRANS; //This line prevents the listenThread from continuously running when socket is closed immediately
        return connectionEstablished;
    }

    /**
     * Transfer all data packets
     */
    public boolean transferData() {

        Thread writerThread = new Thread(new Runnable() {
            @Override
            public void run() {

                while(!completed || swR < swL) {

                    while(swR - swL == sws) {}
                    if(swR >= allPackets.size()) return;

                    TCP sendPacket = allPackets.get(swR);
                    sendTCP(sendPacket);

                    AMOUNT_DATA_TRANS += (sendPacket.getLength() >>> 3);
                    numAcksMap.put(sendPacket.getSequenceNum(), 0);
                    numRetransMap.put(sendPacket.getSequenceNum(), 0);
                    timeoutMap.put(sendPacket.getSequenceNum(), TIME_OUT); //add the current TIME_OUT value for the segment

                    swR++;
                }
            }
        });

        Thread readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(!completed) {
                    TCP receivePacket = receiveTCP();

                    int numAck = 0;
                    if(numAcksMap.containsKey(receivePacket.getAcknowledge())) {
                        numAck = numAcksMap.get(receivePacket.getAcknowledge()) + 1;
                        if(numAck > 1) { NUM_DUPLICATE_ACKS++; }
                    }
                    numAcksMap.put(receivePacket.getAcknowledge(), numAck);
                    
                    seqNum = receivePacket.getAcknowledge();

                    while(swL < allPackets.size() && allPackets.get(swL).getSequenceNum() < seqNum) swL++;

                    if(swL >= allPackets.size()) completed = true;
                }
            }
        });

        TimerTask reTransTask = new TimerTask() {
            @Override
            public void run() {
                for(int i = swL; i < swR; i++) {
                    TCP currPacket = allPackets.get(i);

                    //If numRetrans exceeded for any packet, we exit
                    if (numRetransMap.get(currPacket.getSequenceNum()) >= 16) {
                        System.out.println("Number of Retransmission exceeded for " + currPacket);
                        System.exit(1);
                    }
                    //Check if timeout
                    else if(System.nanoTime() - currPacket.getTimeStamp() > timeoutMap.get(currPacket.getSequenceNum())) {
                        sendTCP(currPacket);
                        NUM_RETRANS++;
                        numRetransMap.put(currPacket.getSequenceNum(), numRetransMap.get(currPacket.getSequenceNum()) + 1);
                    }
                    //Check if ackNum exceeded
                    else if(numAcksMap.get(currPacket.getSequenceNum()) >= 3) {
                        sendTCP(currPacket);
                        NUM_RETRANS++;
                        numRetransMap.put(currPacket.getSequenceNum(), numRetransMap.get(currPacket.getSequenceNum()) + 1);
                    }
                }
            }
        };

        writerThread.start();
        readerThread.start();
        
        Timer timer = new Timer(true); //isDaemon flag is set so that task runs in background
        timer.schedule(reTransTask, 0 , 1000);

        while(!completed){}
        return true;
    }

    boolean connectionTerminated = false; //Placed here so it is visible in threads below
    /**
     * Ends the connection with receiver
     */
    public boolean terminateConnection() {

        this.numRetrans = 0; //Last seen in establishConnection()

        Thread listenThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int expectedSeqNum = seqNum + 1;
                while(true) {
                    TCP recPacket = receiveTCP(); //Listen for FIN-ACK from receiver (may need to do this multiple times)

                    if(recPacket == null) break;

                    else if((recPacket.getAcknowledge() != expectedSeqNum) || (((recPacket.getLength() & TCP.FIN_FLAG) != TCP.FIN_FLAG) && 
                                            ((recPacket.getLength() & TCP.ACK_FLAG) != TCP.ACK_FLAG))) continue;

                    seqNum = recPacket.getAcknowledge(); //Update sequence number because fin counts as "1 byte"
                    TCP ackPacket = new TCP(seqNum, ackNum, System.nanoTime(), (int)TCP.ACK_FLAG, (short)0, null);
                    connectionTerminated = true; //From our perspective, we are good to close socket b/c FIN-ACK received
                    sendTCP(ackPacket);
                }
            }
        });
        listenThread.start();

        while(!connectionTerminated && numRetrans < TCP.MAX_NUM_RETRANS) {
            TCP finPacket = new TCP(this.seqNum, this.ackNum, System.nanoTime(), (int)TCP.FIN_FLAG, (short)0, null);
            this.sendTCP(finPacket);
            numRetrans++;
            try{ Thread.sleep((long)(1000)); } catch(InterruptedException e) { continue; }
        }

        if(numRetrans >= TCP.MAX_NUM_RETRANS) { 
            this.socket.close(); 
            return connectionTerminated; 
        }
        else {
            try{ Thread.sleep((long)(5000)); } catch(InterruptedException e) { }
            this.socket.close(); //We can close after waiting for a while, in-case final ACk from sender is lost

            this.NUM_RETRANS += numRetrans - 1;
            return connectionTerminated;
        }
    }

    /**
     * Simply sends the desired tcpPacket using DatagramSocket.
     * Correct content is responsibility of caller
     */
    public void sendTCP(TCP tcpPacket) {

        tcpPacket.setAcknowledge(this.ackNum); //Set ack field (which rarely changes)

        tcpPacket.setTimeStamp(System.nanoTime()); //Set time field

        byte[] serialized = tcpPacket.serialize(); //Serialize (proper checksum will be added)
        
        //Send
        try {
            DatagramPacket datagramPacket = new DatagramPacket(serialized, serialized.length, 
                                        InetAddress.getByName(this.remoteIP), this.remotePort);

            this.socket.send(datagramPacket);
            
            this.NUM_PACKETS_SENT++;

            System.out.println("snd " + (tcpPacket.getTimeStamp() / 1000000000L) + " " + tcpPacket.getFlags() + 
                    tcpPacket.getSequenceNum() + " " + (tcpPacket.getLength() >>> 3) + " " + tcpPacket.getAcknowledge());

        } catch(UnknownHostException e1) {
            System.out.println("Failed to find host in sendTCP() of TCPsender");
            e1.printStackTrace();
            System.exit(1);
        } catch(IOException e2) {
            //Do nothing, since retransmission limit will take care of it
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
            
            TCP returnPacket = new TCP();
            returnPacket.deserialize(data, 0, data.length);

            System.out.println("rcv " + (returnPacket.getTimeStamp() / 1000000000L) + " " + returnPacket.getFlags() + 
                    returnPacket.getSequenceNum() + " " + (returnPacket.getLength() >>> 3) + " " + returnPacket.getAcknowledge());
            
         
            this.ackNum = returnPacket.getSequenceNum() + 1; //Repeatedly sets ackNum (not necessary but easy)
            calcTimeout(returnPacket);

            return returnPacket;

        } catch (IOException e) {
            return null;
        }
    }

    /** Vars for calculating TIME_OUT (placed here for convenience)*/
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

    /**
     * Prints statistics after a successful TCP sesssion 
     */
    private void printStats() {

        System.out.print(String.format("Amount of Data transferred: %d\n" +  
                                        "Number of packets sent: %d\n" + 
                                        "Number of retransmissions: %d\n" + 
                                        "Number of duplicate acknowledgements: %d\n", 
                                        this.AMOUNT_DATA_TRANS, this.NUM_PACKETS_SENT, this.NUM_RETRANS, this.NUM_DUPLICATE_ACKS));
    }

    @Override
    public String toString() {
        return String.format("portNum: %d | remoteIP: %s | remotePort: %d | filename: %s | mtu: %d | sws: %d", 
                            portNum, remoteIP, remotePort, fileName, mtu, sws);
    }
}