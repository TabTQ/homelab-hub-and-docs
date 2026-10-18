package com.homelabhub.internal.wol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MagicPacketTest {

    @Test
    void buildProducesExpectedPacket() {
        MacAddress mac = MacAddress.parse("AA:BB:CC:DD:EE:FF");
        byte[] packet = MagicPacket.build(mac);

        assertThat(packet).hasSize(102);
        assertThat(Arrays.copyOfRange(packet, 0, 6))
                .containsExactly(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF);
        byte[] macBytes = mac.bytes();
        for (int i = 0; i < 16; i++) {
            assertThat(Arrays.copyOfRange(packet, 6 + i * 6, 6 + i * 6 + 6))
                    .as("repetition %d", i)
                    .containsExactly(macBytes);
        }
    }
}