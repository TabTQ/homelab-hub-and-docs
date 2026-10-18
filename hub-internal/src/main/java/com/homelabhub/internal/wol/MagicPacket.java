package com.homelabhub.internal.wol;

public final class MagicPacket {

    private static final int SYNC_BYTES = 6;
    private static final int REPETITIONS = 16;
    private static final int LENGTH = SYNC_BYTES + SYNC_BYTES * REPETITIONS;

    private MagicPacket() {
    }

    public static byte[] build(MacAddress mac) {
        byte[] macBytes = mac.bytes();
        byte[] packet = new byte[LENGTH];
        for (int i = 0; i < SYNC_BYTES; i++) {
            packet[i] = (byte) 0xFF;
        }
        for (int i = 0; i < REPETITIONS; i++) {
            System.arraycopy(macBytes, 0, packet, SYNC_BYTES + i * SYNC_BYTES, SYNC_BYTES);
        }
        return packet;
    }
}