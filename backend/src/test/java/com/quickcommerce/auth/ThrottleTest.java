package com.quickcommerce.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ThrottleTest {

    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void allowsUpToTheLimitThenBlocksUntilTheWindowEnds() {
        Throttle throttle = new Throttle();
        for (int i = 0; i < 5; i++) {
            assertTrue(throttle.tryAcquire("a", 5, WINDOW, T0));
        }
        assertFalse(throttle.tryAcquire("a", 5, WINDOW, T0));
        assertTrue(throttle.tryAcquire("b", 5, WINDOW, T0), "other keys are not affected");
        assertFalse(throttle.tryAcquire("a", 5, WINDOW, T0.plus(WINDOW).minusSeconds(1)));
        assertTrue(throttle.tryAcquire("a", 5, WINDOW, T0.plus(WINDOW)), "a new window starts fresh");
    }

    @Test
    void resetGivesTheKeyAFreshStart() {
        Throttle throttle = new Throttle();
        for (int i = 0; i < 6; i++) {
            throttle.tryAcquire("a", 5, WINDOW, T0);
        }
        throttle.reset("a");
        assertTrue(throttle.tryAcquire("a", 5, WINDOW, T0));
    }

    @Test
    void exactlyLimitAttemptsPassUnderHeavyParallelUse() throws Exception {
        Throttle throttle = new Throttle();
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int i = 0; i < 10_000; i++) {
            pool.submit(() -> {
                if (throttle.tryAcquire("a", 5, WINDOW, T0)) {
                    allowed.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(5, allowed.get());
    }

    @Test
    void memoryIsBoundedAndExpiredKeysMakeRoom() {
        Throttle throttle = new Throttle();
        for (int i = 0; i < Throttle.MAX_KEYS; i++) {
            assertTrue(throttle.tryAcquire("k" + i, 5, WINDOW, T0));
        }
        assertFalse(throttle.tryAcquire("one-too-many", 5, WINDOW, T0), "full: new keys are refused");
        assertTrue(throttle.tryAcquire("k0", 5, WINDOW, T0), "known keys still work when full");
        assertTrue(throttle.tryAcquire("after-expiry", 5, WINDOW, T0.plus(WINDOW)), "expired keys are purged");
    }
}
