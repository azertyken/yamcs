package org.yamcs.simulation.simulator;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.cfdp.pdu.CfdpPacket;

import com.google.common.util.concurrent.AbstractExecutionThreadService;

public class UdpLink extends AbstractExecutionThreadService {

    private static final Logger log = LoggerFactory.getLogger(UdpLink.class);

    private DatagramSocket socket;
    // TODO, sensical size;
    private byte[] buf = new byte[65536];
    volatile boolean connected;
    final String name;
    private Simulator simulator;
    int port;

    public UdpLink(String name, Simulator simulator, int port) {
        this.name = name;
        this.simulator = simulator;
        this.port = port;
    }

    public void sendPacket(byte[] packet) {
        try {
            if (connected) {
                DatagramPacket dp = new DatagramPacket(packet, packet.length, InetAddress.getByName("localhost"),
                        this.port);
                socket.send(dp);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void run() throws Exception {
        while (isRunning()) {
            if (!connected) {
                connect();
            }
            if (!connected) {
                continue;
            }
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            CfdpPacket cfdp = readPacket(packet);
            simulator.processCfdp(cfdp);
        }
    }

    void connect() {
        if (socket != null) {
            connected = false;
            socket.close();
        }
        try {
            log.info("Waiting for UDP {} connection from server on port {}", name, port);
            socket = new DatagramSocket(10013);
            connected = true;
        } catch (Exception e) {
            if (isRunning()) {
                e.printStackTrace();
            }
        }
    }

    private CfdpPacket readPacket(DatagramPacket packet) {
        return CfdpPacket.getCFDPPacket(ByteBuffer.wrap(packet.getData(), 0, packet.getLength()));
    }

}
