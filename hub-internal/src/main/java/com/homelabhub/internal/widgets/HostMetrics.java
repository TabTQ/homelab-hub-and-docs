package com.homelabhub.internal.widgets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

public final class HostMetrics {

    private static final long MAX_FILE_SIZE = 1024L * 1024L;
    private static final long SAMPLE_STALE_MS = 60_000L;
    private static final long SAMPLE_MIN_INTERVAL_MS = 1_000L;
    private static final long SAMPLE_WAIT_MS = 250L;
    private static final int MAX_TEMP_INDEX = 256;

    private final Path procRoot;
    private final Path sysRoot;
    private final Path diskRoot;
    private final Clock clock;
    private final LongConsumer sleeper;

    private Sample previous;

    public HostMetrics(Path procRoot, Path sysRoot, Path diskRoot, Clock clock, LongConsumer sleeper) {
        this.procRoot = procRoot;
        this.sysRoot = sysRoot;
        this.diskRoot = diskRoot;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public synchronized Map<String, Object> read(Set<String> wanted, String diskPath, String units) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (wanted == null || wanted.isEmpty()) {
            return result;
        }
        boolean imperial = "imperial".equalsIgnoreCase(units);
        boolean needCpu = wanted.contains("cpu");
        boolean needNetwork = wanted.contains("network");

        Double cpuPercent = null;
        Long rxBytesPerSec = null;
        Long txBytesPerSec = null;
        if (needCpu || needNetwork) {
            Sample base;
            Sample fresh;
            long now = clock.millis();
            if (previous == null || now - previous.timestamp > SAMPLE_STALE_MS) {
                base = snapshot();
                sleep();
                fresh = snapshot();
            } else {
                base = previous;
                fresh = snapshot();
                if (clock.millis() - base.timestamp < SAMPLE_MIN_INTERVAL_MS) {
                    sleep();
                    fresh = snapshot();
                }
            }
            long deltaMs = fresh.timestamp - base.timestamp;
            if (needCpu && base.hasCpu && fresh.hasCpu) {
                long totalDelta = fresh.cpuTotal - base.cpuTotal;
                if (totalDelta > 0) {
                    long busyDelta = fresh.cpuBusy - base.cpuBusy;
                    double pct = 100.0 * busyDelta / totalDelta;
                    pct = Math.max(0.0, Math.min(100.0, Math.round(pct * 10.0) / 10.0));
                    cpuPercent = pct;
                }
            }
            if (needNetwork && base.hasNet && fresh.hasNet) {
                rxBytesPerSec = rate(base.netRx, fresh.netRx, deltaMs);
                txBytesPerSec = rate(base.netTx, fresh.netTx, deltaMs);
            }
            previous = fresh;
        }

        if (needCpu && cpuPercent != null) {
            result.put("cpu", single("percent", cpuPercent));
        }
        if (wanted.contains("memory")) {
            Map<String, Object> v = memory();
            if (v != null) {
                result.put("memory", v);
            }
        }
        if (wanted.contains("disk")) {
            Map<String, Object> v = disk(diskPath);
            if (v != null) {
                result.put("disk", v);
            }
        }
        if (wanted.contains("cputemp")) {
            Map<String, Object> v = cputemp(imperial);
            if (v != null) {
                result.put("cputemp", v);
            }
        }
        if (wanted.contains("uptime")) {
            Long seconds = uptime();
            if (seconds != null) {
                result.put("uptime", single("seconds", seconds));
            }
        }
        if (needNetwork && rxBytesPerSec != null && txBytesPerSec != null) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("rxBytesPerSec", rxBytesPerSec);
            v.put("txBytesPerSec", txBytesPerSec);
            result.put("network", v);
        }
        return result;
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    private void sleep() {
        if (sleeper != null) {
            sleeper.accept(SAMPLE_WAIT_MS);
        }
    }

    private Sample snapshot() {
        long[] cpu = parseCpu(readFile(procRoot.resolve("stat")));
        long[] net = parseNet(readFile(procRoot.resolve("net").resolve("dev")));
        return new Sample(clock.millis(), cpu != null, cpu != null ? cpu[0] : 0L, cpu != null ? cpu[1] : 0L,
                net != null, net != null ? net[0] : 0L, net != null ? net[1] : 0L);
    }

    private static long[] parseCpu(String content) {
        if (content == null) {
            return null;
        }
        for (String line : content.split("\n")) {
            if (line.startsWith("cpu ")) {
                String[] t = line.trim().split("\\s+");
                if (t.length < 9) {
                    return null;
                }
                try {
                    long user = Long.parseLong(t[1]);
                    long nice = Long.parseLong(t[2]);
                    long system = Long.parseLong(t[3]);
                    long idle = Long.parseLong(t[4]);
                    long iowait = Long.parseLong(t[5]);
                    long irq = Long.parseLong(t[6]);
                    long softirq = Long.parseLong(t[7]);
                    long steal = Long.parseLong(t[8]);
                    long total = user + nice + system + idle + iowait + irq + softirq + steal;
                    return new long[] {total, total - idle - iowait};
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static long[] parseNet(String content) {
        if (content == null) {
            return null;
        }
        long rx = 0L;
        long tx = 0L;
        boolean found = false;
        for (String line : content.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            if (isExcludedInterface(name)) {
                continue;
            }
            String[] t = line.substring(colon + 1).trim().split("\\s+");
            if (t.length < 9) {
                continue;
            }
            try {
                long rxBytes = Long.parseLong(t[0]);
                long txBytes = Long.parseLong(t[8]);
                rx += rxBytes;
                tx += txBytes;
                found = true;
            } catch (NumberFormatException e) {
                continue;
            }
        }
        return found ? new long[] {rx, tx} : null;
    }

    private static boolean isExcludedInterface(String name) {
        return name.equals("lo") || name.startsWith("veth") || name.startsWith("docker")
                || name.startsWith("br-") || name.startsWith("virbr");
    }

    private static long rate(long prev, long cur, long deltaMs) {
        if (deltaMs <= 0L) {
            return 0L;
        }
        long delta = cur - prev;
        if (delta <= 0L) {
            return 0L;
        }
        return delta * 1000L / deltaMs;
    }

    private Map<String, Object> memory() {
        String content = readFile(procRoot.resolve("meminfo"));
        if (content == null) {
            return null;
        }
        Map<String, Long> kb = new HashMap<>();
        for (String line : content.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String[] t = line.substring(colon + 1).trim().split("\\s+");
            if (t.length == 0) {
                continue;
            }
            try {
                kb.put(key, Long.parseLong(t[0]));
            } catch (NumberFormatException e) {
                continue;
            }
        }
        Long memTotal = kb.get("MemTotal");
        if (memTotal == null || memTotal <= 0L) {
            return null;
        }
        long available;
        Long memAvailable = kb.get("MemAvailable");
        if (memAvailable != null) {
            available = memAvailable;
        } else {
            available = kb.getOrDefault("MemFree", 0L) + kb.getOrDefault("Buffers", 0L) + kb.getOrDefault("Cached", 0L);
        }
        long totalBytes = memTotal * 1024L;
        long availableBytes = available * 1024L;
        long usedBytes = Math.max(0L, totalBytes - availableBytes);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("usedBytes", usedBytes);
        m.put("totalBytes", totalBytes);
        m.put("percent", round1(100.0 * usedBytes / totalBytes));
        return m;
    }

    private Map<String, Object> disk(String diskPath) {
        String p = diskPath == null ? "/" : diskPath;
        if (p.isEmpty() || !p.startsWith("/") || p.contains("..") || p.length() > 200) {
            return null;
        }
        Path resolved = resolve(p);
        try {
            FileStore store = Files.getFileStore(resolved);
            long total = store.getTotalSpace();
            if (total <= 0L) {
                return null;
            }
            long used = Math.max(0L, total - store.getUsableSpace());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", p);
            m.put("usedBytes", used);
            m.put("totalBytes", total);
            m.put("percent", round1(100.0 * used / total));
            return m;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private Path resolve(String diskPath) {
        if (diskRoot == null) {
            return Path.of(diskPath);
        }
        String rel = diskPath.substring(1);
        return rel.isEmpty() ? diskRoot : diskRoot.resolve(rel);
    }

    private Map<String, Object> cputemp(boolean imperial) {
        Path thermal = sysRoot.resolve("class").resolve("thermal");
        for (int i = 0; i < MAX_TEMP_INDEX; i++) {
            Double c = readTempC(thermal.resolve("thermal_zone" + i).resolve("temp"));
            if (c != null) {
                return tempResult(c, imperial);
            }
        }
        Path hwmon = sysRoot.resolve("class").resolve("hwmon");
        for (int i = 0; i < MAX_TEMP_INDEX; i++) {
            Double c = readTempC(hwmon.resolve("hwmon" + i).resolve("temp1_input"));
            if (c != null) {
                return tempResult(c, imperial);
            }
        }
        return null;
    }

    private static Double readTempC(Path p) {
        String content = readFile(p);
        if (content == null) {
            return null;
        }
        String[] t = content.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) {
            return null;
        }
        try {
            double c = Double.parseDouble(t[0]) / 1000.0;
            if (c <= -50.0 || c >= 200.0) {
                return null;
            }
            return c;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> tempResult(double celsius, boolean imperial) {
        double value = imperial ? celsius * 9.0 / 5.0 + 32.0 : celsius;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", round1(value));
        m.put("unit", imperial ? "F" : "C");
        return m;
    }

    private Long uptime() {
        String content = readFile(procRoot.resolve("uptime"));
        if (content == null) {
            return null;
        }
        String[] t = content.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) {
            return null;
        }
        try {
            return (long) Double.parseDouble(t[0]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readFile(Path p) {
        try {
            if (Files.size(p) > MAX_FILE_SIZE) {
                return null;
            }
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static final class Sample {
        final long timestamp;
        final boolean hasCpu;
        final long cpuTotal;
        final long cpuBusy;
        final boolean hasNet;
        final long netRx;
        final long netTx;

        Sample(long timestamp, boolean hasCpu, long cpuTotal, long cpuBusy,
               boolean hasNet, long netRx, long netTx) {
            this.timestamp = timestamp;
            this.hasCpu = hasCpu;
            this.cpuTotal = cpuTotal;
            this.cpuBusy = cpuBusy;
            this.hasNet = hasNet;
            this.netRx = netRx;
            this.netTx = netTx;
        }
    }
}