package com.homelabhub.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class LoginRateLimiter {

    public static final int MAX_TRACKED_KEYS = 10_000;
    private static final String UNKNOWN_KEY = "unknown";

    private final Clock clock;
    private final int maxFailuresPerKey;
    private final long windowMillis;
    private final long baseLockoutMillis;
    private final long maxLockoutMillis;
    private final int globalMaxFailures;
    private final long globalWindowMillis;

    private final Map<String, KeyState> keys = new HashMap<>();
    private final Deque<Long> globalFailures = new ArrayDeque<>();

    public LoginRateLimiter(Clock clock) {
        this(clock, 5, Duration.ofMinutes(15), Duration.ofSeconds(30),
                Duration.ofMinutes(15), 30, Duration.ofMinutes(10));
    }

    public LoginRateLimiter(Clock clock, int maxFailuresPerKey, Duration window, Duration baseLockout,
                            Duration maxLockout, int globalMaxFailures, Duration globalWindow) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (maxFailuresPerKey <= 0) {
            throw new IllegalArgumentException("maxFailuresPerKey must be positive");
        }
        requirePositive(window, "window");
        requirePositive(baseLockout, "baseLockout");
        requirePositive(maxLockout, "maxLockout");
        if (maxLockout.compareTo(baseLockout) < 0) {
            throw new IllegalArgumentException("maxLockout must be >= baseLockout");
        }
        if (globalMaxFailures <= 0) {
            throw new IllegalArgumentException("globalMaxFailures must be positive");
        }
        requirePositive(globalWindow, "globalWindow");
        this.clock = clock;
        this.maxFailuresPerKey = maxFailuresPerKey;
        this.windowMillis = window.toMillis();
        this.baseLockoutMillis = baseLockout.toMillis();
        this.maxLockoutMillis = maxLockout.toMillis();
        this.globalMaxFailures = globalMaxFailures;
        this.globalWindowMillis = globalWindow.toMillis();
    }

    public record Decision(boolean allowed, Duration retryAfter) {}

    public synchronized Decision check(String key) {
        long now = clock.millis();
        pruneGlobal(now);

        long retryAfterMillis = 0;

        KeyState state = keys.get(normalize(key));
        if (state != null) {
            dropStale(state.failures, now - windowMillis);
            int failures = state.failures.size();
            if (failures >= maxFailuresPerKey) {
                long lockout = Math.min(maxLockoutMillis, shiftSat(baseLockoutMillis, failures - maxFailuresPerKey));
                long elapsed = now - state.lastFailure;
                if (elapsed < lockout) {
                    retryAfterMillis = lockout - elapsed;
                }
            }
        }

        if (globalFailures.size() >= globalMaxFailures) {
            long oldest = globalFailures.peekFirst();
            long untilOldestExpires = Math.max(1L, oldest + globalWindowMillis - now);
            retryAfterMillis = Math.max(retryAfterMillis, untilOldestExpires);
        }

        if (retryAfterMillis > 0) {
            return new Decision(false, Duration.ofMillis(retryAfterMillis));
        }
        return new Decision(true, Duration.ZERO);
    }

    public synchronized void recordFailure(String key) {
        long now = clock.millis();
        pruneGlobal(now);
        globalFailures.addLast(now);

        String k = normalize(key);
        KeyState state = keys.get(k);
        if (state == null) {
            if (keys.size() >= MAX_TRACKED_KEYS) {
                evict(now);
            }
            state = new KeyState();
            keys.put(k, state);
        }
        state.failures.addLast(now);
        state.lastFailure = now;
    }

    public synchronized void recordSuccess(String key) {
        pruneGlobal(clock.millis());
        keys.remove(normalize(key));
    }

    public synchronized int trackedKeys() {
        return keys.size();
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private void pruneGlobal(long now) {
        long cutoff = now - globalWindowMillis;
        while (!globalFailures.isEmpty() && globalFailures.peekFirst() <= cutoff) {
            globalFailures.removeFirst();
        }
    }

    private void evict(long now) {
        long cutoff = now - windowMillis;
        Iterator<Map.Entry<String, KeyState>> it = keys.entrySet().iterator();
        while (it.hasNext()) {
            KeyState state = it.next().getValue();
            dropStale(state.failures, cutoff);
            if (state.failures.isEmpty()) {
                it.remove();
            }
        }
        if (keys.size() >= MAX_TRACKED_KEYS) {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, KeyState> entry : keys.entrySet()) {
                if (entry.getValue().lastFailure < oldest) {
                    oldest = entry.getValue().lastFailure;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                keys.remove(oldestKey);
            }
        }
    }

    private static void dropStale(Deque<Long> failures, long cutoff) {
        while (!failures.isEmpty() && failures.peekFirst() <= cutoff) {
            failures.removeFirst();
        }
    }

    private static String normalize(String key) {
        return (key == null || key.isBlank()) ? UNKNOWN_KEY : key;
    }

    private static long shiftSat(long value, int shift) {
        if (shift >= 63 || value > (Long.MAX_VALUE >> shift)) {
            return Long.MAX_VALUE;
        }
        return value << shift;
    }

    private static final class KeyState {
        final Deque<Long> failures = new ArrayDeque<>();
        long lastFailure;
    }
}