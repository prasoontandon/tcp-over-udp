import java.nio.ByteBuffer;

public class TCPTests {

    public static void main(String[] args) {
        System.out.println("\nAll tests for TCP passed: " + runTCPTests());
    }

    public static boolean runTCPTests() {
        return runCalcChecksumTests() && runSerializeTests() && runDeserializeTests();
    }

    public static boolean runCalcChecksumTests() {
        boolean passed = true;

        //Test 1: Small case
        {
            byte[] data = {(byte) 0x86, (byte) 0x5E, (byte) 0xAC, (byte) 0x60};

            short expected = (short) 0xCD40;
            short result = TCP.calcChecksum(data);
            if(expected != result) {
                System.out.println("Test 1 for calcChecksum() failed! expected: " + expected + " result: " + result);
                passed = false;
            }
        }

        //Test 2: Medium case
        {
            byte[] data = {(byte) 0x86, (byte) 0x5E, (byte) 0xAC, (byte) 0x60, 
                            (byte) 0x71, (byte) 0x2A};

            short expected = (short) 0x5C16;
            short result = TCP.calcChecksum(data);
            if(expected != result) {
                System.out.println("Test 2 for calcChecksum() failed! expected: " + expected + " result: " + result);
                passed = false;
            }
        }

        //Test 3: Provided example case
        {
            byte[] data = {(byte) 0x86, (byte) 0x5E, (byte) 0xAC, (byte) 0x60, (byte) 0x71, 
                            (byte) 0x2A, (byte) 0x81, (byte) 0xB5};
            short expected = (short) 0xDA60;
            short result = TCP.calcChecksum(data);
            if(expected != result) {
                System.out.println("Test 3 for calcChecksum() failed! expected: " + expected + " result: " + result);
                passed = false;
            }
        }

        return passed;
    }

    public static boolean runSerializeTests() {
        boolean passed = true;

        //Test Case 1: Happy path with no data
        {
            TCP testPacket = new TCP(255, 15, 41651, 5, (short)0, null);

            byte[] expected = new byte[24];
            expected[3] = (byte)0xFF; expected[7] = 0x0F; expected[14] = (byte)0xA2; 
            expected[15] = (byte)0xB3; expected[19] = 0x05; 
            expected[22] = 0x5C; expected[23] = 0x39; //Checksum of the whole packet

            byte[] actual = testPacket.serialize();
            
            if(actual == null || expected.length != actual.length){
                System.out.println("Test 1 for serialize() failed at length matching!");
                passed = false;
            } else{
                for(int i = 0; i < expected.length; i++) {
                    if(expected[i] != actual[i]){
                        System.out.println("Test 1 for serialize() failed at comparison indx: " + i + "/" + expected.length + 
                            " expected: " + expected[i] + " result: " + actual[i]);
                        passed = false;
                        break;
                    }
                }
            }
        }

        //Test Case 2: Happy path with data
        {


        }

        //Test Case 3: Edge Case
        {


        }

        //Test Case 4: Edge Case
        {


        }


        return passed;
    }

    public static boolean runDeserializeTests() {
        boolean passed = true;
        
        //Test Case 1: Happy path with no data
        {           
            byte[] packet = new byte[24];
            packet[3] = (byte)0xFF; packet[7] = 0x0F; packet[14] = (byte)0xA2; 
            packet[15] = (byte)0xB3; packet[19] = 0x05; 
            packet[22] = 0x5C; packet[23] = 0x39; //Checksum of the whole packet

            TCP actual = (new TCP()).deserialize(packet, 0, packet.length);
            TCP expected = new TCP(255, 15, 41651, 5, (short)0x5C39, null);

            if(!expected.equals(actual)) {
                System.out.println("Test 1 for deserialize() failed! Expected: " + expected + 
                                        "\nActual: " + actual);
                passed = false;

            }
        }

        return passed;
    }

}