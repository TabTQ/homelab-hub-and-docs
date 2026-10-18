package com.homelabhub.internal.widgets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostMetricsTest {

    @TempDir
    Path temp;

    private Path proc;
    private Path sys;

    private static final String STAT_SAMPLE1 =
            "cpu  1000 0 0 1000 0 0 0 0 0 0\n"
            + "cpu0 123 0 0 456 0 0 0 0 0 0\n";
    private static final String STAT_SAMPLE2 =
            "cpu  1100 0 0 1100 0 0 0 0 0 0\n"
            + "cpu0 223 0 0 556 0 0 0 0 0 0\n";

    private static final String MEMINFO = """
            MemTotal:       16359328 kB
            MemFree:         6020812 kB
            MemAvailable:    12379328 kB
            Buffers:          299400 kB
            Cached:          5994520 kB
            SwapCached:            0 kB
            """;

    private static final String MEMINFO_NO_AVAILABLE = """
            MemTotal:        1000000 kB
            MemFree:          400000 kB
            Buffers:           50000 kB
            Cached:           150000 kB
            """;

    private static final String NETDEV1 = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo: 100000  1000 0 0 0 0 0 0 100000  1000 0 0 0 0 0 0
              eth0:   1500    15 0 0 0 0 0 0   2510    20 0 0 0 0 0 0
          tailscale0:   4000    40 0 0 0 0 0 0   6000    60 0 0 0 0 0 0
             veth1234: 1000000 1000 0 0 0 0 0 0 1000000 1000 0 0 0 0 0 0
              docker0: 1000000 1000 0 0 0 0 0 0 1000000 1000 0 0 0 0 0 0
               br-abc: 1000000 1000 0 0 0 0 0 0 1000000 1000 0 0 0 0 0 0
               virbr0: 1000000 1000 0 0 0 0 0 0 1000000 1000 0 0 0 0 0 0
            """;

    private static final String NETDEV2 = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo: 100000  1000 0 0 0 0 0 0 100000  1000 0 0 0 0 0 0
              eth0:   3500    35 0 0 0 0 0 0   4510    40 0 0 0 0 0 0
          tailscale0:   8000    80 0 0 0 0 0 0  12000   120 0 0 0 0 0 0
            """;

    private static final String NETDEV_EXCL1 = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo: 1000 10 0 0 0 0 0 0 1000 10 0 0 0 0 0 0
              eth0: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
             veth1: 100000 100 0 0 0 0 0 0 100000 100 0 0 0 0 0 0
            docker0: 1000000 1000 0 0 0 0 0 0 1000000 1000 0 0 0 0 0 0
            """;

    private static final String NETDEV_EXCL2 = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo: 2000 20 0 0 0 0 0 0 2000 20 0 0 0 0 0 0
              eth0: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
             veth1: 200000 200 0 0 0 0 0 0 200000 200 0 0 0 0 0 0
            docker0: 2000000 2000 0 0 0 0 0 0 2000000 2000 0 0 0 0 0 0
            """;

    private static final String NETDEV_LOW = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
              eth0:    300 3 0 0 0 0 0 0 400 4 0 0 0 0 0 0
          tailscale0:    200 2 0 0 0 0 0 0 100 1 0 0 0 0 0 0
            """;

    private static final String NETDEV_HIGH = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
              eth0:   5000 50 0 0 0 0 0 0 5000 50 0 0 0 0 0 0
          tailscale0:   1000 10 0 0 0 0 0 0 1000 10 0 0 0 0 0 0
            """;

    @BeforeEach
    void setUp() throws IOException {
        proc = temp.resolve("proc");
        sys = temp.resolve("sys");
        Files.createDirectories(proc);
        Files.createDirectories(sys);
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private Path netdev() {
        return proc("net").resolve("dev");
    }

    private Path proc(String... parts) {
        return path(proc, parts);
    }

    private Path sys(String... parts) {
        return path(sys, parts);
    }

    private Path path(Path root, String... parts) {
        Path p = root;
        for (String part : parts) {
            p = p.resolve(part);
        }
        return p;
    }

    private void thermalTemp(String zone, String value) throws IOException {
        write(sys("class", "thermal", zone, "temp"), value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static LongConsumer noop() {
        return ms -> { };
    }

    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long ms) {
            millis += ms;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }

        @Override
        public long millis() {
            return millis;
        }
    }

    private static final class RewritingSleeper implements LongConsumer {
        final List<Long> calls = new ArrayList<>();
        private final MutableClock clock;
        private final Path stat;
        private final Path netdev;
        private final String statAfter;
        private final String netdevAfter;

        RewritingSleeper(MutableClock clock, Path stat, Path netdev, String statAfter, String netdevAfter) {
            this.clock = clock;
            this.stat = stat;
            this.netdev = netdev;
            this.statAfter = statAfter;
            this.netdevAfter = netdevAfter;
        }

        @Override
        public void accept(long ms) {
            calls.add(ms);
            clock.advance(ms);
            try {
                Files.writeString(stat, statAfter, StandardCharsets.UTF_8);
                Files.writeString(netdev, netdevAfter, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void fullFixture() throws IOException {
        write(proc("stat"), STAT_SAMPLE1);
        write(proc("meminfo"), MEMINFO);
        write(proc("uptime"), "74243.55 251233.10\n");
        write(proc("net", "dev"), NETDEV1);
        thermalTemp("thermal_zone0", "51121\n");
    }

    private HostMetrics metrics(MutableClock clock, LongConsumer sleeper) {
        return new HostMetrics(proc, sys, null, clock, sleeper);
    }

    // ------------------------------------------------------------ sampling math

    @Test
    void everyKeyWithTwoSampleMath() throws Exception {
        fullFixture();
        MutableClock clock = new MutableClock(1_000_000L);
        RewritingSleeper sleeper = new RewritingSleeper(clock, proc("stat"), netdev(), STAT_SAMPLE2, NETDEV2);
        HostMetrics metrics = metrics(clock, sleeper);

        Map<String, Object> res = metrics.read(allKeys(), null, null);

        assertThat(sleeper.calls).containsExactly(250L);
        assertThat(res.keySet()).containsExactly("cpu", "memory", "disk", "cputemp", "uptime", "network");
        assertThat(map(res.get("cpu"))).containsExactly(entry("percent", 50.0));

        Map<String, Object> mem = map(res.get("memory"));
        assertThat(mem).contains(entry("totalBytes", 16_751_951_872L), entry("usedBytes", 4_075_520_000L), entry("percent", 24.3));

        Map<String, Object> disk = map(res.get("disk"));
        assertThat(disk.get("path")).isEqualTo("/");
        assertThat((Long) disk.get("totalBytes")).isPositive();
        assertThat((Long) disk.get("usedBytes")).isBetween(0L, (Long) disk.get("totalBytes"));
        assertThat((Double) disk.get("percent")).isBetween(0.0, 100.0);

        assertThat(map(res.get("cputemp"))).contains(entry("value", 51.1), entry("unit", "C"));
        assertThat(map(res.get("uptime"))).contains(entry("seconds", 74243L));
        assertThat(map(res.get("network"))).contains(entry("rxBytesPerSec", 24_000L), entry("txBytesPerSec", 32_000L));
    }

    private static Set<String> allKeys() {
        return Set.of("cpu", "memory", "disk", "cputemp", "uptime", "network");
    }

    @Test
    void memoryWithMemAvailable() {
        writeNo(() -> write(proc("meminfo"), MEMINFO));
        HostMetrics metrics = metrics(new MutableClock(0L), noop());
        Map<String, Object> res = metrics.read(Set.of("memory"), null, null);
        assertThat(map(res.get("memory")))
                .contains(entry("totalBytes", 16_751_951_872L), entry("usedBytes", 4_075_520_000L), entry("percent", 24.3));
    }

    @Test
    void memoryWithoutMemAvailable() {
        writeNo(() -> write(proc("meminfo"), MEMINFO_NO_AVAILABLE));
        HostMetrics metrics = metrics(new MutableClock(0L), noop());
        Map<String, Object> res = metrics.read(Set.of("memory"), null, null);
        assertThat(map(res.get("memory")))
                .contains(entry("totalBytes", 1_024_000_000L), entry("usedBytes", 409_600_000L), entry("percent", 40.0));
    }

    // ------------------------------------------------------------ sampling rule

    @Test
    void noSleepWhenLaterCallIsMoreThanOneSecondAfter() throws Exception {
        write(proc("stat"), STAT_SAMPLE1);
        write(netdev(), NETDEV1);
        MutableClock clock = new MutableClock(1_000_000L);
        RewritingSleeper sleeper = new RewritingSleeper(clock, proc("stat"), netdev(), STAT_SAMPLE2, NETDEV2);
        HostMetrics metrics = metrics(clock, sleeper);

        metrics.read(Set.of("cpu", "network"), null, null);
        assertThat(sleeper.calls).containsExactly(250L);

        clock.advance(2_000L);
        Map<String, Object> res = metrics.read(Set.of("cpu", "network"), null, null);

        assertThat(sleeper.calls).containsExactly(250L);
        assertThat(res).doesNotContainKey("cpu");
        assertThat(map(res.get("network"))).contains(entry("rxBytesPerSec", 0L), entry("txBytesPerSec", 0L));
    }

    @Test
    void rapidSecondCallSleepsBeforeResampling() throws Exception {
        write(proc("stat"), STAT_SAMPLE1);
        write(netdev(), NETDEV1);
        MutableClock clock = new MutableClock(1_000_000L);
        RewritingSleeper sleeper = new RewritingSleeper(clock, proc("stat"), netdev(), STAT_SAMPLE2, NETDEV2);
        HostMetrics metrics = metrics(clock, sleeper);

        metrics.read(Set.of("cpu", "network"), null, null);
        metrics.read(Set.of("cpu", "network"), null, null);

        assertThat(sleeper.calls).containsExactly(250L, 250L);
    }

    @Test
    void counterDecreaseReportsZeroAndAdoptsNewBaseline() throws Exception {
        write(proc("stat"), STAT_SAMPLE1);
        write(netdev(), NETDEV1);
        MutableClock clock = new MutableClock(1_000_000L);
        RewritingSleeper sleeper = new RewritingSleeper(clock, proc("stat"), netdev(), STAT_SAMPLE2, NETDEV2);
        HostMetrics metrics = metrics(clock, sleeper);

        metrics.read(Set.of("network"), null, null); // baseline rx 5500, tx 8510

        write(netdev(), NETDEV_LOW); // rx 500, tx 500
        clock.advance(2_000L);
        Map<String, Object> res = metrics.read(Set.of("network"), null, null);
        assertThat(map(res.get("network"))).contains(entry("rxBytesPerSec", 0L), entry("txBytesPerSec", 0L));

        write(netdev(), NETDEV_HIGH); // rx 6000, tx 6000 -> +5500 per 2000 ms
        clock.advance(2_000L);
        Map<String, Object> res2 = metrics.read(Set.of("network"), null, null);
        assertThat(map(res2.get("network"))).contains(entry("rxBytesPerSec", 2_750L), entry("txBytesPerSec", 2_750L));
    }

    @Test
    void excludedInterfacesAreNotSummed() throws Exception {
        write(proc("stat"), STAT_SAMPLE1);
        write(netdev(), NETDEV_EXCL1);
        MutableClock clock = new MutableClock(1_000_000L);
        RewritingSleeper sleeper = new RewritingSleeper(clock, proc("stat"), netdev(), STAT_SAMPLE2, NETDEV_EXCL2);
        HostMetrics metrics = metrics(clock, sleeper);

        Map<String, Object> res = metrics.read(Set.of("network"), null, null);

        assertThat(map(res.get("network"))).contains(entry("rxBytesPerSec", 0L), entry("txBytesPerSec", 0L));
    }

    // ------------------------------------------------------------ cputemp

    @Test
    void imperialConversion() throws Exception {
        thermalTemp("thermal_zone0", "51121\n");
        HostMetrics metrics = metrics(new MutableClock(0L), noop());

        Map<String, Object> imperial = metrics.read(Set.of("cputemp"), null, "imperial");
        assertThat(map(imperial.get("cputemp"))).contains(entry("value", 124.0), entry("unit", "F"));

        Map<String, Object> metric = metrics.read(Set.of("cputemp"), null, null);
        assertThat(map(metric.get("cputemp"))).contains(entry("value", 51.1), entry("unit", "C"));
    }

    @Test
    void cputempFallsBackToHwmon() throws Exception {
        write(sys("class", "hwmon", "hwmon0", "temp1_input"), "45678\n");
        HostMetrics metrics = metrics(new MutableClock(0L), noop());

        Map<String, Object> res = metrics.read(Set.of("cputemp"), null, null);
        assertThat(map(res.get("cputemp"))).contains(entry("value", 45.7), entry("unit", "C"));
    }

    @Test
    void bogusTemperaturesAreSkipped() throws Exception {
        thermalTemp("thermal_zone0", "250000\n");
        thermalTemp("thermal_zone1", "-50000\n");
        thermalTemp("thermal_zone2", "45123\n");
        HostMetrics metrics = metrics(new MutableClock(0L), noop());

        Map<String, Object> res = metrics.read(Set.of("cputemp"), null, null);
        assertThat(map(res.get("cputemp"))).contains(entry("value", 45.1), entry("unit", "C"));
    }

    // ------------------------------------------------------------ disk

    @Test
    void diskOnATempDirectory() {
        Path dir = temp.resolve("diskfixture");
        ensureDir(dir);
        HostMetrics metrics = metrics(new MutableClock(0L), noop());

        Map<String, Object> res = metrics.read(Set.of("disk"), dir.toString(), null);

        Map<String, Object> disk = map(res.get("disk"));
        assertThat(disk.get("path")).isEqualTo(dir.toString());
        assertThat((Long) disk.get("totalBytes")).isPositive();
        assertThat((Long) disk.get("usedBytes")).isBetween(0L, (Long) disk.get("totalBytes"));
        assertThat((Double) disk.get("percent")).isBetween(0.0, 100.0);
    }

    @Test
    void diskRootPrefixesResolvedPath() {
        Path root = temp.resolve("host-root");
        ensureDir(root.resolve("mnt").resolve("data"));
        HostMetrics metrics = new HostMetrics(proc, sys, root, new MutableClock(0L), noop());

        Map<String, Object> res = metrics.read(Set.of("disk"), "/mnt/data", null);
        Map<String, Object> disk = map(res.get("disk"));
        assertThat(disk.get("path")).isEqualTo("/mnt/data");
        assertThat((Long) disk.get("totalBytes")).isPositive();

        Map<String, Object> res2 = metrics.read(Set.of("disk"), "/", null);
        assertThat(map(res2.get("disk")).get("path")).isEqualTo("/");
        assertThat((Long) map(res2.get("disk")).get("totalBytes")).isPositive();
    }

    @Test
    void invalidDiskPathsAreRejected() {
        HostMetrics metrics = metrics(new MutableClock(0L), noop());
        List<String> bad = List.of("relative", "/a/../b", "/" + "a".repeat(200));

        for (String p : bad) {
            Map<String, Object> res = metrics.read(Set.of("disk"), p, null);
            assertThat(res).doesNotContainKey("disk");
        }
    }

    // ------------------------------------------------------------ omission, robustness

    @Test
    void missingFilesOmitOnlyTheirKey() throws Exception {
        HostMetrics metrics = metrics(new MutableClock(0L), noop());
        Map<String, Object> res = metrics.read(Set.of("cpu", "memory", "cputemp", "uptime", "network"), null, null);
        assertThat(res).isEmpty();

        write(proc("stat"), STAT_SAMPLE1);
        write(proc("meminfo"), MEMINFO);
        write(proc("uptime"), "74243.55 251233.10\n");
        Files.createDirectories(proc("net"));
        MutableClock clock = new MutableClock(1_000_000L);
        RewritingSleeper sleeper = new RewritingSleeper(clock, proc("stat"), netdev(), STAT_SAMPLE2, NETDEV2);
        HostMetrics metrics2 = metrics(clock, sleeper);
        Map<String, Object> res2 = metrics2.read(Set.of("cpu", "memory", "cputemp", "uptime", "network"), null, null);
        assertThat(res2.keySet()).containsExactly("cpu", "memory", "uptime");
    }

    @Test
    void garbageBinaryEmptyAndOversizedContentNeverThrows() throws Exception {
        write(proc("stat"), "not cpu data at all\n%%% \n\n");
        Files.write(proc("meminfo"), new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0xfd});
        write(proc("uptime"), "");
        Files.createDirectories(proc("net"));
        Files.write(netdev(), new byte[0]);

        HostMetrics metrics = metrics(new MutableClock(0L), noop());
        Map<String, Object> res = metrics.read(Set.of("cpu", "memory", "uptime", "network", "cputemp"), null, null);
        assertThat(res).isEmpty();

        byte[] big = new byte[2 * 1024 * 1024];
        Arrays.fill(big, (byte) 'x');
        Files.write(proc("stat"), big);
        Map<String, Object> res2 = metrics.read(Set.of("cpu"), null, null);
        assertThat(res2).doesNotContainKey("cpu");

        write(sys("class", "thermal", "thermal_zone0", "temp"), "");
        Files.write(sys("class", "thermal", "thermal_zone0", "temp"), new byte[2 * 1024 * 1024]);
        Map<String, Object> res3 = metrics.read(Set.of("cputemp"), null, null);
        assertThat(res3).doesNotContainKey("cputemp");
    }

    @Test
    void wantedSubsetIsReturnedInCanonicalOrder() throws Exception {
        fullFixture();
        MutableClock clock = new MutableClock(1_000_000L);
        RewritingSleeper sleeper = new RewritingSleeper(clock, proc("stat"), netdev(), STAT_SAMPLE2, NETDEV2);
        HostMetrics metrics = metrics(clock, sleeper);

        Set<String> wanted = new LinkedHashSet<>();
        wanted.add("network");
        wanted.add("uptime");
        wanted.add("disk");
        wanted.add("cpu");

        Map<String, Object> res = metrics.read(wanted, null, null);

        assertThat(res.keySet()).containsExactly("cpu", "disk", "uptime", "network");
    }

    @Test
    void nullDiskPathAndUnitsUseTheirDefaults() throws Exception {
        thermalTemp("thermal_zone0", "51121\n");
        HostMetrics metrics = metrics(new MutableClock(0L), noop());

        Map<String, Object> res = metrics.read(Set.of("disk", "cputemp"), null, null);

        assertThat(map(res.get("disk")).get("path")).isEqualTo("/");
        assertThat(map(res.get("cputemp"))).contains(entry("value", 51.1), entry("unit", "C"));
    }

    @Test
    void concurrentReadsDoNotThrow() throws Exception {
        fullFixture();
        HostMetrics metrics = metrics(new MutableClock(0L), noop());
        int threads = 8;
        int iterations = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        try {
                            metrics.read(Set.of("cpu", "memory", "cputemp", "uptime", "network"), null, null);
                        } catch (Exception ex) {
                            failure.compareAndSet(null, ex);
                            throw ex;
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(failure.get()).isNull();
    }

    private interface IoFunction {
        void run() throws IOException;
    }

    private void writeNo(IoFunction fn) {
        try {
            fn.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void ensureDir(Path d) {
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}