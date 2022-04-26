package com.ethanf108.tftplug;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TftpRouteServer {

    public static final int MAX_CONNECTIONS = 4;
    public static final long MAX_TIMEOUT = 10_000;

    final DatagramSocket socket;
    private final Thread listenThread;
    private final transient Map<String, DataSender> activeConnections;
    private final transient PlugManager plugManager;
    private boolean debug = false;
    private transient boolean isRunning;

    public TftpRouteServer(int port) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.listenThread = new Thread(this::listen, "TFTP Listen Thread");
        this.activeConnections = new HashMap<>();
        this.isRunning = true;
        this.listenThread.start();
        this.plugManager = new PlugManager(Credentials.PXE_SECRET);
    }

    private static String readNullTerminatedString(DataInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte read;
        while ((read = in.readByte()) != 0) {
            buf.write(read);
        }
        return buf.toString();
    }

    void debug(String s) {
        if (this.debug) {
            System.out.println(s);
        }
    }

    void sendError(InetAddress addr, int port, int errorCode, String errorMessage) throws IOException {
        ByteBuffer build = ByteBuffer.allocate(5 + (errorMessage == null ? 0 : errorMessage.length()));
        build.put((byte) 0);
        build.put((byte) 5);
        build.putShort((short) errorCode);
        if (errorMessage != null && !errorMessage.isEmpty()) {
            build.put(errorMessage.getBytes());
        }
        build.put((byte) 0);
        DatagramPacket send = new DatagramPacket(build.array(), build.capacity(), addr, port);
        this.socket.send(send);
        this.debug("Error code " + errorCode + " sent to client " + addr.getHostAddress() + ", " + errorMessage);
    }

    private void listen() {
        try {
            byte[] buf = new byte[65536];
            while (this.isRunning) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                this.socket.receive(packet);
                if (packet.getLength() < 2) {
                    this.sendError(packet.getAddress(), packet.getPort(), 4, "bruh");
                    continue;
                }
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(buf, 0, packet.getLength()));
                try {
                    final int opcode = in.readUnsignedShort();
                    switch (opcode) {
                        //Read Request
                        case 1 -> {
                            final String filename = readNullTerminatedString(in);
                            final String mode = readNullTerminatedString(in);
                            if(!mode.equalsIgnoreCase("octet")){
                                this.sendError(packet.getAddress(), packet.getPort(), 2, "Octet only boyo");
                                break;
                            }
                            Map<String, String> options = new HashMap<>();
                            while (true) {
                                try {
                                    options.put(readNullTerminatedString(in), readNullTerminatedString(in));
                                } catch (EOFException e) {
                                    break;
                                }
                            }
                            this.debug("Sending file " + filename + " mode: " + mode);

                            InputStream readStream;
                            long size;
                            if (filename.equals("plug.png")) {
                                final byte[] plugImage = this.plugManager.getPlugImage();
                                readStream = new ByteArrayInputStream(plugImage);
                                size = plugImage.length;
                            } else {
                                File file = new File(Credentials.TFTP_PATH + filename);
                                if (!file.exists()) {
                                    this.sendError(packet.getAddress(), packet.getPort(), 1, "File not found :( sorry");
                                    break;
                                }
                                if (!file.getCanonicalPath().startsWith(Credentials.TFTP_PATH)) {
                                    this.sendError(packet.getAddress(), packet.getPort(), 2, "No Hacking >:(");
                                    break;
                                }
                                readStream = new FileInputStream(file);
                                size = file.length();
                            }
                            this.prune();
                            if (this.activeConnections.size() >= MAX_CONNECTIONS) {
                                this.sendError(packet.getAddress(), packet.getPort(), 0, "Too many connections bud, try later");
                                break;
                            }
                            DataSender sender = new DataSender(
                                    this,
                                    packet.getAddress(),
                                    packet.getPort(),
                                    readStream,
                                    Integer.parseInt(options.getOrDefault("blksize", "512"))
                            );
                            this.activeConnections.put(packet.getAddress().getHostAddress(), sender);
                            if (options.isEmpty()) {
                                sender.ack(0);
                            } else {
                                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                                buffer.write(0);
                                buffer.write(6);
                                for (Map.Entry<String, String> pair : options.entrySet()) {
                                    buffer.write(pair.getKey().getBytes());
                                    buffer.write(0);
                                    buffer.write(pair.getKey().equals("tsize") ? String.valueOf(size).getBytes() : pair.getValue().getBytes());
                                    buffer.write(0);
                                }
                                DatagramPacket oack = new DatagramPacket(buffer.toByteArray(), buffer.size(), packet.getAddress(), packet.getPort());
                                this.socket.send(oack);
                            }
                        }
                        //Write Request
                        case 2 -> this.sendError(packet.getAddress(), packet.getPort(), 2, "Writing not allowed");
                        //Data
                        case 3 -> this.sendError(packet.getAddress(), packet.getPort(), 2, "I said, writing is not allowed! Jeez!");
                        //ACK
                        case 4 -> {
                            DataSender sender = this.activeConnections.get(packet.getAddress().getHostAddress());
                            final int blockNum = in.readUnsignedShort();
                            if (sender == null) {
                                break;
                            }
                            sender.ack(blockNum);
                        }
                        //Error
                        case 5 -> {
                            final int errorCode = in.readUnsignedShort();
                            this.debug("Error code " + errorCode + " from Client: " + readNullTerminatedString(in));
                        }
                        case 6 -> {
                            this.debug("Received OACK Packet???? From Client?? ok bro you do you");
                            this.sendError(packet.getAddress(), packet.getPort(), 0, "Why u tryna OACK me bruv");
                        }
                        default -> this.sendError(packet.getAddress(), packet.getPort(), 4, "bruh what this be");

                    }
                } catch (EOFException e) {
                    this.sendError(packet.getAddress(), packet.getPort(), 0, "Malformed TFTP Packet");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void prune() {
        List<String> toRemove = new ArrayList<>();
        for (String addr : this.activeConnections.keySet()) {
            DataSender ds = this.activeConnections.get(addr);
            if (ds.delayTime() > MAX_TIMEOUT) {
                ds.terminate();
            }
            if (!ds.isRunning()) {
                toRemove.add(addr);
            }
        }
        toRemove.forEach(this.activeConnections::remove);
    }

    public void stop() {
        this.isRunning = false;
    }

    public static void main(String[] args) throws IOException {
        TftpRouteServer server = new TftpRouteServer(69);
        if (args.length > 0 && args[0].equals("-v")) {
            server.debug = true;
        }
    }
}
