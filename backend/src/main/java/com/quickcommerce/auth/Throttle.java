package com.quickcommerce.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Counts attempts per key (an email, an IP address) in a fixed time window.
 *
 * The count goes up BEFORE the caller does its work, in one atomic step, so
 * requests arriving at the same moment cannot all slip past the limit.
 *
 * ponytail: in-memory and per instance. With two backend instances each one
 * counts alone, so the real limit doubles. Move to a shared store (Redis /
 * bucket4j) when we scale out.
 */
@Component
public class Throttle {

    /** Hard cap on tracked keys so an attacker cannot grow the map without end. */
    static final int MAX_KEYS = 100_000;

    private record Window(int count, Instant expiresAt) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** Counts one attempt for the key. False when the key has no attempts left in its window. */
    public boolean tryAcquire(String key, int limit, Duration window, Instant now) {
        if (windows.size() >= MAX_KEYS) {
            windows.values().removeIf(w -> !w.expiresAt().isAfter(now));
            if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) {
                return false; // still full of live keys: refuse new ones rather than run out of memory
            }
        }
        Window w = windows.merge(key, new Window(1, now.plus(window)),
                (old, fresh) -> old.expiresAt().isAfter(now) ? new Window(old.count() + 1, old.expiresAt()) : fresh);
        return w.count() <= limit;
    }

    public void reset(String key) {
        windows.remove(key);
    }
}
