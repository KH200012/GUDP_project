import java.io.IOException;
import java.net.SocketException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class GUDPSocket implements GUDPSocketAPI {


    public GUDPSender send = new GUDPSender(GUDPPacket.MAX_WINDOW_SIZE);
    public Thread sender = new Thread(send, "GUDP Sender");

    private final DatagramSocket datagramSocket;
    public List<Integer> ackbuffer = new ArrayList<>();
    public boolean receivestate = false;

    public GUDPReceiver receive = new GUDPReceiver(ackbuffer);
    public Thread receiver = new Thread(receive, "GUDP Receiver");

    public GUDPSocket(DatagramSocket socket) throws SocketException {
        datagramSocket = socket;
    }

    public void send(DatagramPacket packet) throws IOException {
        send.DataToSendingQueueadd(packet);
    }

    public void receive(DatagramPacket packet) throws IOException {
        if (!receivestate) {
            receiver.start();
            receivestate = true;
            System.out.println("Receiver Start");
        }
        receive.receive(packet);
    }

    public void finish() throws IOException {
        sender.start();
        System.out.println("Sender Start");
    }

    public void close() throws IOException {
    }



    class GUDPSender implements Runnable {
        private final int WindowSize;

        public int firstIndex;
        public int lastIndex;
        public int WindowSizenow;

        public final List<GUDPBuffer> sendingqueue = new ArrayList<>();
        public final List<Integer> ACKBuffer = new ArrayList<>();
        public InetSocketAddress address;
        public int bsnSeqNumber;
        public int seqNumber;

        public int count = 0;
        public int indexackbuffer = 0;
        public double sendpercent = 0;

        public boolean sendingexit = false;


        public GUDPSender(int dataWindowSize) throws SocketException {
            this.WindowSize = dataWindowSize;
            this.WindowSizenow = this.WindowSize;
            this.BsnToSendingQueueadd();
        }

        public void BsnToSendingQueueadd() {
            bsnSeqNumber = new Random().nextInt(Short.MAX_VALUE);
            seqNumber = bsnSeqNumber;
            firstIndex = 0;
            lastIndex = 1;
            ByteBuffer buffer = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
            buffer.order(ByteOrder.BIG_ENDIAN);
            GUDPPacket packet = new GUDPPacket(buffer);
            packet.setSeqno(seqNumber);
            packet.setPayloadLength(0);
            packet.setType(GUDPPacket.TYPE_BSN);
            packet.setVersion(GUDPPacket.GUDP_VERSION);

            seqNumber++;
            sendingqueue.add(new GUDPBuffer(packet));
        }

        public void DataToSendingQueueadd(DatagramPacket packet) throws IOException {
            address = (InetSocketAddress) packet.getSocketAddress();
            GUDPPacket gudppacket = GUDPPacket.encapsulate(packet);
            gudppacket.setSeqno(seqNumber);
            seqNumber++;
            sendingqueue.add(new GUDPBuffer(gudppacket));
        }



        public void firstIndexupdate() {
            for (int index = firstIndex; index < lastIndex; index++) {
                GUDPBuffer block = sendingqueue.get(index);
                if (block.ackReceived) {
                    firstIndex++;
                    if (firstIndex == 1) {
                        System.out.println("send data");
                    }
                    continue;
                }
                break;
            }
        }
        public void windowupdate(List<Integer> ACKBuffer) throws IOException {
            for (int i = indexackbuffer; i < ACKBuffer.size(); i++) {
                int index = ACKBuffer.get(i) - 1 - bsnSeqNumber;
                GUDPBuffer packetinqueue = sendingqueue.get(index);
                if (!packetinqueue.ackReceived) {
                    packetinqueue.ackReceived = true;
                    count++;
                }
                indexackbuffer++;
            }
            firstIndexupdate();
            if (count != sendingqueue.size()) {
                sendpercent = (double) count / sendingqueue.size() * 100;

                if (sendpercent > 0) {
                    System.out.println("already sent:" + String.format("%.2f", sendpercent) + "%");
                }
                DataPacketssend();
            }
            if (count == sendingqueue.size()) {
                System.out.println("sending completed");
                stop();
            }

        }

        public void timeoutresend() throws IOException {
            for (int index = firstIndex; index < lastIndex; index++) {
                GUDPBuffer packetinqueue = sendingqueue.get(index);
                if (packetinqueue.isTimeout() & packetinqueue.retrytimes < 20) {
                    send(packetinqueue);
                    packetinqueue.retrytimes++;
                    System.out.println("resend packet" + (packetinqueue.packet.getSeqno() - bsnSeqNumber) + ":" + packetinqueue.retrytimes + "times");
                }
                if (packetinqueue.retrytimes == 20) {
                    System.out.println("sending is failed,there are too many retry times!");
                    this.stop();
                }
            }
        }

        public void send(GUDPBuffer packet) throws IOException {
            packet.Sentmark();
            packet.packet.setSocketAddress(address);
            datagramSocket.send(packet.packet.pack());
        }

        private void DataPacketssend() throws IOException {
            int Indexnewlast = Math.min(sendingqueue.size(), firstIndex + WindowSizenow);
            for (int index = firstIndex; index < Indexnewlast; index++) {
                GUDPBuffer packet = sendingqueue.get(index);
                if (!packet.sent) {
                    send(packet);
                }
            }
            lastIndex = Indexnewlast;
        }

        public void stop() {
            sendingexit = true;
            System.out.println("Sender Close");
            System.exit(1);
        }

        public void run() {
            GUDPReceiver ackreceiver = null;
            try {
                ackreceiver = new GUDPReceiver(ACKBuffer);
            } catch (SocketException e) {
                throw new RuntimeException(e);
            }
            Thread AckReceiver = new Thread(ackreceiver, "ACK Receiver");
            AckReceiver.start();
            System.out.println("ACKreceiver Start");
            while (!sendingexit) {
                try {
                    windowupdate(ACKBuffer);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                try {
                    timeoutresend();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

        }
    }

    class GUDPBuffer {

        boolean sent = false;
        boolean ackReceived;
        int retrytimes = 0;

        final Duration TIMEOUT = Duration.ofMillis(1000);
        final GUDPPacket packet;
        Instant resendTime;

        public GUDPBuffer(GUDPPacket packet) {
            this.packet = packet;
        }

        public void Sentmark() {
            resendTime = Instant.now()
                    .plus(TIMEOUT)
                    .minusNanos(1);
            sent = true;
        }

        public boolean isTimeout() {
            return sent && !ackReceived && Instant.now().isAfter(resendTime);
        }
    }

    class GUDPReceiver implements Runnable {
        private final Queue<GUDPPacket> Packetsbuffer = new PriorityQueue<>(Comparator.comparingInt(GUDPPacket::getSeqno));
        private final BlockingQueue<GUDPPacket> receivingQueue = new LinkedBlockingQueue<>();
        private int expectSeqNumber = 0;
        public List<Integer> ackBuffer;
        private boolean running = true;

        public GUDPReceiver(List<Integer> ackbuffer) throws SocketException {
            this.ackBuffer = ackbuffer;
        }

        public void receive(DatagramPacket packet) throws IOException {
            try {
                GUDPPacket gudpPacket = receivingQueue.take();
                gudpPacket.decapsulate(packet);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }

        private void packetsreceive() throws IOException {
            byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
            DatagramPacket udpPacket = new DatagramPacket(buf, buf.length);
            datagramSocket.receive(udpPacket);
            GUDPPacket gudppacket = GUDPPacket.unpack(udpPacket);
            short type = gudppacket.getType();
            switch (type) {
                case GUDPPacket.TYPE_BSN:
                    expectSeqNumber = gudppacket.getSeqno() + 1;
                    Packetsbuffer.add(gudppacket);
                    Acksend(gudppacket);
                    break;
                case GUDPPacket.TYPE_DATA:
                    if(gudppacket.getSeqno()==expectSeqNumber) {
                        Packetsbuffer.add(gudppacket);
                        Acksend(gudppacket);
                        break;
                    }
                case GUDPPacket.TYPE_ACK:
                    ackBuffer.add(gudppacket.getSeqno());
                    break;
            }
        }

        private void Acksend(GUDPPacket Packet) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
            buffer.order(ByteOrder.BIG_ENDIAN);
            GUDPPacket ackPacket = new GUDPPacket(buffer);
            ackPacket.setType(GUDPPacket.TYPE_ACK);
            ackPacket.setVersion(GUDPPacket.GUDP_VERSION);
            ackPacket.setSeqno(Packet.getSeqno() + 1);
            ackPacket.setPayloadLength(0);
            ackPacket.setSocketAddress(Packet.getSocketAddress());
            datagramSocket.send(ackPacket.pack());
        }
        private void saveToReceivingQueue() throws IOException {
            while (true) {
                GUDPPacket packet = Packetsbuffer.peek();
                if (packet == null) {
                    break;
                }
                int seqNumber = packet.getSeqno();
                if (seqNumber < expectSeqNumber) {
                    Packetsbuffer.remove();
                    continue;
                }
                Packetsbuffer.remove();
                receivingQueue.add(packet);
                expectSeqNumber++;
                }
            }

        public void run() {
            while (this.running) {
                try {
                    packetsreceive();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                try {
                    saveToReceivingQueue();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}

