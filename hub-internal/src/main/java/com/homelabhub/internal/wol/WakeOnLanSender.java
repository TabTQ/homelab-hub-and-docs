package com.homelabhub.internal.wol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class WakeOnLanSender {

    private static final String BROADCAST_ADDRESS = "255.255.255.255";
    private static final int DEFAULT_PORT = 9;

    private final InetAddress target;
    private final int port;
    private final int repeat;
    private final long delayMillis;

    public WakeOnLanSender(InetAddress target, int port, int repeat, long delayMillis) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535: " + port);
        }
        if (repeat < 1) {
            throw new IllegalArgumentException("Repeat must be at least 1: " + repeat);
        }
        if (delayMillis < 0) {
            throw new IllegalArgumentException("Delay must not be negative: " + delayMillis);
        }
        this.target = target;
        this.port = port;
        this.repeat = repeat;
        this.delayMillis = delayMillis;
    }

    public static WakeOnLanSender defaultSender() {
        try {
            return new WakeOnLanSender(InetAddress.getByName(BROADCAST_ADDRESS), DEFAULT_PORT, 3, 100L);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    public void send(MacAddress mac) {
        byte[] packet = MagicPacket.build(mac);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            DatagramPacket datagram = new DatagramPacket(packet, packet.length, target, port);
            for (int i = 0; i < repeat; i++) {
                socket.send(datagram);
                if (i < repeat - 1) {
                    sleepDelay();
                }
            }
        } catch (IOException e) {
            throw new WakeOnLanException("Failed to send WOL packet: " + e.getMessage(), e);
        }
    }

    private void sleepDelay() {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WakeOnLanException("Interrupted while sending WOL packet");
        }
    }
}