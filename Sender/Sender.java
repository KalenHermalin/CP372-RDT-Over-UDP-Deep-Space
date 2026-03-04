import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

public class Sender {

	public static void main(String[] args) {
		// Not enough args to start the server. Let user know
		if (args.length < 5) {
			System.out.println("Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
			return;
		}
		
		try {
			String rcvIP = args[0];
			int rcvDataPort = Integer.parseInt(args[1]);
			int senderAckPort = Integer.parseInt(args[2]);
			String inputFile = args[3];
			int timeoutMs = Integer.parseInt(args[4]);

			try (DatagramSocket socket = new DatagramSocket(senderAckPort);
			     FileInputStream file = new FileInputStream(new File(inputFile))) {

				InetAddress receiverAddr = InetAddress.getByName(rcvIP);
				socket.setSoTimeout(timeoutMs);
				long startTime = System.currentTimeMillis();
				if (args.length == 5) {
					StopAndWait(socket, receiverAddr, rcvDataPort, file);
				}
				else {
					// Perform window size checks to insure multiple of 4
					// then run the GBN protocol
					int windowSize = Integer.parseInt(args[5]);
					if (windowSize > 128) {
						System.err.println("Error: Window size is greater than 128. (Requirement: window_size <= 128)");
						System.exit(1);
					}
					if (windowSize % 4 != 0) {
						System.err.println("Error: Window size is not a multiple of 4");
						System.exit(1);
					}
					GoBackN(socket, receiverAddr, rcvDataPort, windowSize, file);
				}

				long endTime = System.currentTimeMillis();
				System.out.printf("Total Transmission Time: %.2f seconds\n", (endTime - startTime) / 1000.0);
			} // The file and socket are safely closed right here

		} catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
			return;
		}

	}

	private static void GoBackN(DatagramSocket socket, InetAddress receiverAddr, int rcvDataPort, int windowSize, FileInputStream file) throws IOException {
		throw new UnsupportedOperationException("Unimplemented protocol 'GoBackN'");
	}

	private static void sendAndWaitForAck(DatagramSocket socket, InetAddress receiverAddr, int rcvDataPort,
			byte typeSot, int currentSeq, byte[] payload) {
		DSPacket packet = new DSPacket(typeSot, currentSeq, payload);
		byte[] packetBytes = packet.toBytes();
		DatagramPacket outPacket = new DatagramPacket(packetBytes, packetBytes.length, receiverAddr, rcvDataPort);

		int timeoutCount = 0;

		while(true) {
			try {
				socket.send(outPacket);
			} catch (IOException e) {
				System.err.println("Error: Sending Packet" + e.getMessage());
				System.exit(1);

			}

			try {
				byte[] ackBuff = new byte[DSPacket.MAX_PACKET_SIZE];
				DatagramPacket receivedPacket = new DatagramPacket(ackBuff, ackBuff.length);
				socket.receive(receivedPacket);

				DSPacket ackPacket = new DSPacket(receivedPacket.getData());

				// We only advance to next packet if we get an ack matching the current packet number 
				if (ackPacket.getType() == DSPacket.TYPE_ACK && ackPacket.getSeqNum() == currentSeq) {
					break; // Move to next packet
				}

			} catch (SocketTimeoutException e) {
				timeoutCount++;
				if (timeoutCount >=3) {
					System.err.println("Error: Unable to transfer file");
					System.exit(1);
				}
			} catch (IOException e) {
				System.err.println("Error: Receiving Packet" + e.getMessage());
				System.exit(1);

			}

		}
	}

	private static void StopAndWait(DatagramSocket socket, InetAddress receiverAddr, int rcvDataPort, FileInputStream file) throws IOException {
				int currentSeq = 0; // Stop and wait starts at sequence number 0
				// Start/Send handshake
				sendAndWaitForAck(socket, receiverAddr, rcvDataPort, DSPacket.TYPE_SOT, currentSeq, null);
				currentSeq = (currentSeq + 1) % 128;

				// Payload buffer
				byte[] buffer = new byte[DSPacket.MAX_PAYLOAD_SIZE];
				// Size of payload
				int bytesRead;
				boolean isFileEmpty = true;

				// Loop while there is still data to read from file
				while ((bytesRead = file.read(buffer)) != -1) {
					isFileEmpty = false;
					// creates payload of exact size of bytes we read
					byte[] payload = new byte[bytesRead];
					// Copy buffer payload to the new exactly sized payload
					System.arraycopy(buffer, 0, payload, 0, bytesRead);

					sendAndWaitForAck(socket, receiverAddr, rcvDataPort, DSPacket.TYPE_DATA, currentSeq, payload);
					currentSeq = (currentSeq + 1) % 128;

				}
				if (isFileEmpty) {
					currentSeq = 1;
				}

				sendAndWaitForAck(socket, receiverAddr, rcvDataPort, DSPacket.TYPE_EOT, currentSeq, null);

	}
}
