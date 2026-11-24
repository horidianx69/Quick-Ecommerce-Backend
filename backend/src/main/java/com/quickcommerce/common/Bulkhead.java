package com.quickcommerce.common;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Caps how many calls of one kind run at the same moment. A caller that cannot
 * get a slot within the wait gets 503 "busy, retry" at once, instead of queuing
 * threads and database connections behind a burst.
 */
public class Bulkhead {

    private final Semaphore slots;
    private final long waitMillis;
    private final String busyMessage;

    public Bulkhead(int maxConcurrent, long waitMillis, String busyMessage) {
        this.slots = new Semaphore(maxConcurrent, true);
        this.waitMillis = waitMillis;
        this.busyMessage = busyMessage;
    }

    public <T> T run(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, busyMessage);
        }
        try {
            return work.get();
        } finally {
            slots.release();
        }
    }
}
