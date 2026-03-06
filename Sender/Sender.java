import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

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

	private static void GoBackN(DatagramSocket socket, InetAddress receiverAddr, int rcvDataPort,
                            int windowSize, FileInputStream file) throws IOException {

    // Send SOT packet 
    int sotSeq = 0;
    sendAndWaitForAck(socket, receiverAddr, rcvDataPort, DSPacket.TYPE_SOT, sotSeq, null);

    // Read the file into chunks 
    List<byte[]> chunks = new ArrayList<>();
    byte[] buf = new byte[DSPacket.MAX_PAYLOAD_SIZE];
    int bytesRead;

    while ((bytesRead = file.read(buf)) != -1) {
        byte[] payload = new byte[bytesRead];
        System.arraycopy(buf, 0, payload, 0, bytesRead);
        chunks.add(payload);
    }

    // First data packet uses sequence #1
    int firstDataSeq = 1;
    int totalPackets = chunks.size();

    // Give every chunck a sequence number
    int[] seqs = new int[totalPackets];
    int seq = firstDataSeq;
    for (int i = 0; i < totalPackets; i++) {
        seqs[i] = seq;
        seq = (seq + 1) % 128;
    }

    // Calculate EOT using (last DATA seq + 1) mod 128
    int eotSeq;
    if (totalPackets == 0) {
        eotSeq = 1;
    } else {
        eotSeq = (seqs[totalPackets - 1] + 1) % 128;
    }

    // GBN State Variaables
    int baseIndex = 0;      // first packet waiting for ACK
    int nextIndex = 0;      // next packet to be sent
    int lastAckedSeq = 0;   // last cumulatively acked seq (start at SOT=0)

	// keeps track of timeouts (if == 3 transfer fails)
    int consecutiveTimeouts = 0;

    // Main GBN Loop --> Loops until all packets ACK (baseindex reaches totalpackets)
    while (baseIndex < totalPackets) {

        // Loop to send more packets if there's room in the window
        while (nextIndex < totalPackets && (nextIndex - baseIndex) < windowSize) {
            sendDataPacket(socket, receiverAddr, rcvDataPort, seqs[nextIndex], chunks.get(nextIndex));
            nextIndex++;
        }

        // Wait for an packet (should be an ACK)
        try {
            byte[] ackBuf = new byte[DSPacket.MAX_PACKET_SIZE];
            DatagramPacket ackDp = new DatagramPacket(ackBuf, ackBuf.length);
            socket.receive(ackDp);

            DSPacket ack = new DSPacket(ackDp.getData());
            if (ack.getType() != DSPacket.TYPE_ACK) {
                continue;
            }

            int ackSeq = ack.getSeqNum();
            int baseSeq = seqs[baseIndex];
            int dist = (ackSeq - baseSeq + 128) % 128;

			//determine how many packets are waiting to be ACK
            int inFlight = (nextIndex - 1) - baseIndex;
            if (inFlight >= 0 && dist <= inFlight) {

                baseIndex = baseIndex + dist + 1;

                // reset timeout count
                consecutiveTimeouts = 0;
                lastAckedSeq = ackSeq;

                // If window empty sync nextIndex to baseIndex
                if (baseIndex > nextIndex) {
                    nextIndex = baseIndex;
                }
            }

		// catches timeouts (From earlier if timeout == 3)
        } catch (SocketTimeoutException e) {
            consecutiveTimeouts++;
			// if no progress after 3 timeouts --> error
            if (consecutiveTimeouts >= 3) {
                System.err.println("Error: Unable to transfer file");
                System.exit(1);
            }

            // if timeout --> resend packets 
            for (int i = baseIndex; i < nextIndex; i++) {
    			sendDataPacket(socket, receiverAddr, rcvDataPort, seqs[i], chunks.get(i));
				}
        }
    }

    // Once all packets are ACK --> EOT 
    sendAndWaitForAck(socket, receiverAddr, rcvDataPort, DSPacket.TYPE_EOT, eotSeq, null);
}
private static void sendDataPacket(
        DatagramSocket socket,
        InetAddress receiverAddr,
        int rcvDataPort,
        int seq,
        byte[] payload) throws IOException {

    DSPacket packet = new DSPacket(DSPacket.TYPE_DATA, seq, payload);
    byte[] bytes = packet.toBytes();

    DatagramPacket out = new DatagramPacket(bytes, bytes.length, receiverAddr, rcvDataPort);
    socket.send(out);
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