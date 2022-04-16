package com.ethanf108.tftplug;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class DataSender extends Thread {

    private final TftpRouteServer server;
    private final InetAddress address;
    private final int port;
    private final InputStream in;
    private final int blockSize;

    private final transient BlockingQueue<Integer> ackQueue;
    private transient long lastOfferTime = System.currentTimeMillis();
    private transient boolean isRunning;

    public DataSender(TftpRouteServer server, InetAddress address, int port, InputStream in, int blockSize) {
        super("TFTP Send for " + address.getHostAddress());
        this.server = server;
        this.address = address;
        this.port = port;
        this.in = in;
        this.blockSize = blockSize;
        this.ackQueue = new ArrayBlockingQueue<>(4, false);
        this.setDaemon(true);
        this.start();
    }

    @Override
    public void run() {
        this.isRunning = true;
        try {
            while (this.isRunning) {
                try {
                    final int blockNum = this.ackQueue.take();
                    ByteBuffer buf = ByteBuffer.allocate(4 + this.blockSize);
                    buf.putShort((short) 3);
                    buf.putShort((short) (blockNum + 1));
                    int numBytes = this.blockSize;
                    while (numBytes-- > 0) {
                        int read = this.in.read();
                        if (read == -1) {
                            this.isRunning = false;
                            break;
                        }
                        buf.put((byte) read);
                    }
                    DatagramPacket send = new DatagramPacket(buf.array(), buf.position(), this.address, this.port);
                    this.server.socket.send(send);
                    if (!this.isRunning) {
                        this.server.debug("Done sending file to " + this.address.getHostAddress());
                    }
                } catch (InterruptedException e) {
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    void ack(int blockNum) {
        if (!this.ackQueue.offer(blockNum)) {
            throw new RuntimeException("Too many queued ACK's");
        }
        this.lastOfferTime = System.currentTimeMillis();
    }

    public long delayTime() {
        return System.currentTimeMillis() - this.lastOfferTime;
    }

    public boolean isRunning() {
        return this.isRunning;
    }

    void terminate() {
        this.isRunning = false;
        this.interrupt();
        try {
            this.server.sendError(this.address, this.port, 0, "sorry bub but YA DONE");
        } catch (IOException e) {
            //bruh;
        }
    }
}
