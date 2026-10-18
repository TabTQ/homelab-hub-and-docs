package com.homelabhub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    private static final long START = 1_000_000_000_000L;

    @Test
    void allowedInitially() {
        LoginRateLimiter limiter = new LoginRateLimiter(clock());
        LoginRateLimiter.Decision d = limiter.check("1.2.3.4");
        assertThat(d.allowed()).isTrue();
        assertThat(d.retryAfter()).isEqualTo(Duration.ZERO);
    }

    @Test
    void fourFailuresStillAllowedThenFifthBlocks() {
        MutableClock clock = clock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("1.2.3.4");
            assertThat(limiter.check("1.2.3.4").allowed()).isTrue();
        }
        limiter.recordFailure("1.2.3.4");
        LoginRateLimiter.Decision d = limiter.check("1.2.3.4");
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void retryAfterCountsDownAndAccessReturnsExactlyWhenLockoutEnds() {
        MutableClock clock = clock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.check("1.2.3.4").retryAfter()).isEqualTo(Duration.ofSeconds(20));
        clock.advance(Duration.ofSeconds(19));
        assertThat(limiter.check("1.2.3.4").retryAfter()).isEqualTo(Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.check("1.2.3.4").allowed()).isTrue();
    }

    @Test
    void lockoutDoublesPerFailurePastThresholdAndCapsAtMaxLockout() {
        MutableClock clock = clock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        limiter.recordFailure("1.2.3.4");
        assertThat(limiter.check("1.2.3.4").retryAfter()).isEqualTo(Duration.ofSeconds(60));
        limiter.recordFailure("1.2.3.4");
        assertThat(limiter.check("1.2.3.4").retryAfter()).isEqualTo(Duration.ofSeconds(120));
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        assertThat(limiter.check("1.2.3.4").retryAfter()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void failuresOutsideWindowAreForgotten() {
        MutableClock clock = clock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        assertThat(limiter.check("1.2.3.4").allowed()).isFalse();
        clock.advance(Duration.ofMinutes(16));
        assertThat(limiter.check("1.2.3.4").allowed()).isTrue();
    }

    @Test
    void successClearsTheKey() {
        MutableClock clock = clock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        assertThat(limiter.check("1.2.3.4").allowed()).isFalse();
        limiter.recordSuccess("1.2.3.4");
        LoginRateLimiter.Decision d = limiter.check("1.2.3.4");
        assertThat(d.allowed()).isTrue();
        assertThat(d.retryAfter()).isEqualTo(Duration.ZERO);
    }

    @Test
    void successOnOneKeyDoesNotUnblockAnother() {
        MutableClock clock = clock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("a");
            limiter.recordFailure("b");
        }
        assertThat(limiter.check("a").allowed()).isFalse();
        assertThat(limiter.check("b").allowed()).isFalse();
        limiter.recordSuccess("a");
        assertThat(limiter.check("a").allowed()).isTrue();
        assertThat(limiter.check("b").allowed()).isFalse();
    }

    @Test
    void keysAreIndependent() {
        MutableClock clock = clock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("a");
        }
        assertThat(limiter.check("b").allowed()).isTrue();
    }

    @Test
    void globalLimitBlocksANewKeyAndLiftsAsFailuresAgeOut() {
        MutableClock clock = clock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock, 5, Duration.ofMinutes(15), Duration.ofSeconds(30),
                Duration.ofMinutes(15), 10, Duration.ofMinutes(5));
        for (int i = 0; i < 10; i++) {
            limiter.recordFailure("user-" + i);
        }
        LoginRateLimiter.Decision d = limiter.check("brand-new");
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfter()).isEqualTo(Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(5));
        assertThat(limiter.check("brand-new").allowed()).isTrue();
    }

    @Test
    void nullAndBlankKeysShareOneBucket() {
        MutableClock clock = clock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        assertThat(limiter.check(null).allowed()).isTrue();
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(null);
        }
        assertThat(limiter.check("").allowed()).isFalse();
        assertThat(limiter.check("   ").allowed()).isFalse();
        assertThat(limiter.check("other").allowed()).isTrue();
    }

    @Test
    void constructorValidation() {
        MutableClock clock = clock();
        assertThatThrownBy(() -> new LoginRateLimiter(null)).isInstanceOf(IllegalArgumentException.class);

        Duration w = Duration.ofMinutes(15);
        Duration b = Duration.ofSeconds(30);
        Duration m = Duration.ofMinutes(15);
        Duration gw = Duration.ofMinutes(10);
        assertThatThrownBy(() -> new LoginRateLimiter(clock, 0, w, b, m, 30, gw)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(clock, 5, Duration.ZERO, b, m, 30, gw)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(clock, 5, Duration.ofMinutes(-1), b, m, 30, gw)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(clock, 5, w, Duration.ZERO, m, 30, gw)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(clock, 5, w, b, b.minusSeconds(1), 30, gw)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(clock, 5, w, b, m, 0, gw)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(clock, 5, w, b, m, 30, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentFailuresDoNotThrowAndLeaveAllKeysBlocked() throws Exception {
        MutableClock clock = clock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock, 5, Duration.ofMinutes(15),
                Duration.ofSeconds(30), Duration.ofMinutes(15), Integer.MAX_VALUE, Duration.ofMinutes(10));
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                futures.add(pool.submit(() -> {
                    String key = "k" + (tid % 4);
                    for (int i = 0; i < perThread; i++) {
                        limiter.recordFailure(key);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.check("k" + i).allowed()).as("key k%d", i).isFalse();
        }
        assertThat(limiter.trackedKeys()).isEqualTo(4);
    }

    @Test
    void trackedKeysStaysUnderTenThousand() {
        MutableClock clock = clock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock, Integer.MAX_VALUE, Duration.ofMinutes(15),
                Duration.ofSeconds(30), Duration.ofMinutes(15), Integer.MAX_VALUE, Duration.ofMinutes(10));
        for (int i = 0; i < 10_500; i++) {
            limiter.recordFailure("key-" + i);
            assertThat(limiter.trackedKeys()).as("after insert %d", i).isLessThanOrEqualTo(LoginRateLimiter.MAX_TRACKED_KEYS);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(LoginRateLimiter.MAX_TRACKED_KEYS);
    }

    private static MutableClock clock() {
        return new MutableClock(START);
    }

    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}