package com.homelabhub.internal.wol;

import java.util.Arrays;
import java.util.regex.Pattern;

public final class MacAddress {

    private static final Pattern FORMAT = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$");

    private final byte[] bytes;

    private MacAddress(byte[] bytes) {
        this.bytes = bytes;
    }

    public static MacAddress parse(String input) {
        if (input != null && FORMAT.matcher(input.trim()).matches()) {
            byte[] parsed = new byte[6];
            String[] octets = input.trim().split("[:-]");
            for (int i = 0; i < parsed.length; i++) {
                parsed[i] = (byte) Integer.parseInt(octets[i], 16);
            }
            return new MacAddress(parsed);
        }
        throw new IllegalArgumentException("Invalid MAC address format: " + input
                + ". Expected format: XX:XX:XX:XX:XX:XX or XX-XX-XX-XX-XX-XX");
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5]);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MacAddress)) {
            return false;
        }
        MacAddress other = (MacAddress) o;
        return Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}