package com.homelabhub.internal.wol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WakeOnLanSenderTest {

    private static final byte[] EXPECTED_PAYLOAD =
            MagicPacket.build(MacAddress.parse("AA:BB:CC:DD:EE:FF"));

    @Test
    void sendsExactlyThreeIdenticalPackets() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            receiver.setSoTimeout(2000);
            WakeOnLanSender sender =
                    new WakeOnLanSender(InetAddress.getLoopbackAddress(), receiver.getLocalPort(), 3, 0);
            sender.send(MacAddress.parse("AA:BB:CC:DD:EE:FF"));

            for (int i = 0; i < 3; i++) {
                DatagramPacket received = receive(receiver);
                assertThat(received.getLength()).isEqualTo(102);
                assertThat(Arrays.copyOf(received.getData(), received.getLength()))
                        .containsExactly(EXPECTED_PAYLOAD);
            }
            assertThatThrownBy(() -> receiver.receive(new DatagramPacket(new byte[102], 102)))
                    .isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void sendsExactlyOnePacketWhenRepeatIsOne() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            receiver.setSoTimeout(2000);
            WakeOnLanSender sender =
                    new WakeOnLanSender(InetAddress.getLoopbackAddress(), receiver.getLocalPort(), 1, 0);
            sender.send(MacAddress.parse("AA:BB:CC:DD:EE:FF"));

            DatagramPacket received = receive(receiver);
            assertThat(received.getLength()).isEqualTo(102);
            assertThat(Arrays.copyOf(received.getData(), received.getLength()))
                    .containsExactly(EXPECTED_PAYLOAD);
            assertThatThrownBy(() -> receiver.receive(new DatagramPacket(new byte[102], 102)))
                    .isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void delayIsRespectedBetweenSends() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            receiver.setSoTimeout(4000);
            WakeOnLanSender sender = new WakeOnLanSender(
                    InetAddress.getLoopbackAddress(), receiver.getLocalPort(), 3, 150);

            long start = System.nanoTime();
            sender.send(MacAddress.parse("AA:BB:CC:DD:EE:FF"));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).isGreaterThanOrEqualTo(290L);

            for (int i = 0; i < 3; i++) {
                receive(receiver);
            }
        }
    }

    @Test
    void constructorRejectsInvalidArguments() {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        assertThatThrownBy(() -> new WakeOnLanSender(loopback, 0, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WakeOnLanSender(loopback, 65536, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WakeOnLanSender(loopback, 9, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WakeOnLanSender(loopback, 9, 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void interruptingTheSenderDuringDelayThrowsAndLeavesInterruptFlagSet() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            receiver.setSoTimeout(2000);
            WakeOnLanSender sender = new WakeOnLanSender(
                    InetAddress.getLoopbackAddress(), receiver.getLocalPort(), 3, 200);

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread senderThread = new Thread(() -> {
                try {
                    sender.send(MacAddress.parse("AA:BB:CC:DD:EE:FF"));
                } catch (WakeOnLanException e) {
                    failure.set(e);
                }
            });
            senderThread.start();
            senderThread.join(50);
            senderThread.interrupt();
            senderThread.join(3000);

            assertThat(senderThread.isAlive()).isFalse();
            assertThat(failure.get())
                    .isInstanceOf(WakeOnLanException.class)
                    .hasMessage("Interrupted while sending WOL packet");
            assertThat(senderThread.isInterrupted()).isTrue();
        }
    }

    private static DatagramPacket receive(DatagramSocket receiver) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[102], 102);
        receiver.receive(packet);
        return packet;
    }
}