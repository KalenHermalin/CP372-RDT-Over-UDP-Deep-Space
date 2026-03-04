import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

public class Receiver {
	public static void main(String[] args) {
		if (args.length < 5) {
			System.out.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN> [window_size]");
			return;
		}

		try {
			String senderIP = args[0];
			int senderAckPort = Integer.parseInt(args[1]);
			int rcvDataPort = Integer.parseInt(args[2]);
			String outputFile = args[3];
			int rn = Integer.parseInt(args[4]);

			// if windowSize is provided, then we parse it and the receiver will act as a GBN receiver
			// else we set window size to 1 and receiver acts as a Stop-And-Wait receiver
			int windowSize = args.length == 6 ? Integer.parseInt(args[5]) : 1; 

			try (DatagramSocket socket = new DatagramSocket(rcvDataPort);
					FileOutputStream file = new FileOutputStream(new File(outputFile))) {

				InetAddress senderAddr = InetAddress.getByName(senderIP);
				int expectedSeq = 0; // First packet expected is SOT 
				int lastAckSeq = -1; // Represents the largest continous in order sequence that has been delivered
				int ackCount = 0;

				// A buffer for out of order packets
				Map<Integer, DSPacket> buffer = new HashMap<>();

				byte[] receiveBuffer = new byte[DSPacket.MAX_PACKET_SIZE];
				DatagramPacket recPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);

				boolean transferComplete = false;
				boolean EOTReceived = false;

				while (!transferComplete) {
					socket.receive(recPacket);
					DSPacket receivedPacket = new DSPacket(recPacket.getData());

					int seq = receivedPacket.getSeqNum();
					int type = receivedPacket.getType();

					int distance = (seq - expectedSeq);
					if (distance < 0) {distance += 128;}
					if (distance < windowSize) {
						buffer.putIfAbsent(seq, receivedPacket);

						while(buffer.containsKey(expectedSeq)) {
							DSPacket packetDeliver = buffer.get(expectedSeq);

							if (packetDeliver.getType() == DSPacket.TYPE_DATA) {
								file.write(packetDeliver.getPayload());
							} else if (packetDeliver.getType() == DSPacket.TYPE_EOT) {
								EOTReceived = true;
							}
							buffer.remove(expectedSeq);
							lastAckSeq = expectedSeq;
							expectedSeq = (expectedSeq + 1) %128;

						}
					}
					// If distance is greater than or equal to window size, its an old/duplicate packet or too far ahead
					// Resend last ack
					int ack = (lastAckSeq == -1) ? 0 : lastAckSeq;
					DSPacket ackPacket = new DSPacket(DSPacket.TYPE_ACK, ack, null);
					byte[] ackBytes = ackPacket.toBytes();
					DatagramPacket sendPacket = new DatagramPacket(ackBytes, ackBytes.length, senderAddr, senderAckPort);
					ackCount++;

					if (!ChaosEngine.shouldDrop(ackCount, rn)) {
						socket.send(sendPacket);
						if (EOTReceived) {transferComplete = true;}
					}else {
						System.out.println("Simulating drop of ACK for seq: " + ack);
					}

				}
					}		
		} catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
		}

	}

}
