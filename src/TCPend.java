public class TCPend {

    /** Overall statistics that will be modified by senders and receivers*/
    public static int AMOUNT_DATA_TRANS_TRANS = 0;
    public static int AMOUNT_DATA_TRANS_REC = 0;
    public static int NUM_PACKETS_SENT = 0;
    public static int NUM_PACKETS_REC = 0;
    public static int NUM_OOS_PACKETS_DISCARDED = 0;
    public static int NUM_PACKETS_DISCARDED_CHECKSUM = 0;
    public static int NUM_RETRANS = 0;
    public static int NUM_DUPLICATE_ACKS = 0;

    public static void main(String[] args) {

        if(args.length != 12 && args.length != 8) printUsage();

        int portNum = 0;
        int remoteIP = 0;
        int remotePort = 0;
        String fileName = null;
        int mtu = 0;
        int sws = 0;

        for(int i = 0; i < args.length - 1; i++) {
            String arg = args[i];

            if(arg.equals("-p")) {
                portNum = Integer.parseInt(args[++i]);
            }
            else if(arg.equals("-s")) {
                remoteIP = Integer.parseInt(args[++i]);
            }
            else if(arg.equals("-a")) {
                remotePort = Integer.parseInt(args[++i]);
            }
            else if(arg.equals("-f")) {
                fileName = args[++i];
            }
            else if(arg.equals("-m")) {
                mtu = Integer.parseInt(args[++i]);
            }
            else if(arg.equals("-c")) {
                sws = Integer.parseInt(args[++i]);
            }
        }

        if(args.length == 12) {
            TCPsender sender = new TCPsender(portNum, remoteIP, remotePort, fileName, mtu, sws);
            System.out.println("Created Sender with => " + sender);
            sender.run();
        } else {
            TCPreceiver receiver = new TCPreceiver(portNum, mtu, sws, fileName);
            System.out.println("Created Receiver with => " + receiver);
            receiver.run();
        }

        printStats();
    }

    public static void printStats(){

        //TODO
    }

    public static void printUsage(){
        System.out.print("Usage:\n" +
                        "Sender: java TCPend -p <port> -s <remote IP> -a <remote port> f <file name> -m <mtu> -c <sws>\n" +
                        "Receiver: java TCPend -p <port> -m <mtu> -c <sws> -f <file name>\n");
    }
}