package com.m2smartremote.core;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-key token bucket. Buckets idle for longer than a minute are dropped. */
public final class RateLimiter {
    private static final long IDLE_EVICT_MS = 60_000;

    private final Clock clock;
    private final double capacity;
    private final double refillPerMs;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private long lastSweep;

    private static final class Bucket {
        double tokens;
        long updatedAt;
    }

    public RateLimiter(Clock clock, int capacity, double refillPerSecond) {
        this.clock = clock;
        this.capacity = capacity;
        this.refillPerMs = refillPerSecond / 1000.0;
    }

    public boolean tryAcquire(String key) {
        long now = clock.nowMillis();
        sweep(now);
        Bucket b = buckets.get(key);
        if (b == null) {
            Bucket fresh = new Bucket();
            fresh.tokens = capacity;
            fresh.updatedAt = now;
            b = buckets.putIfAbsent(key, fresh);
            if (b == null) {
                b = fresh;
            }
        }
        synchronized (b) {
            b.tokens = Math.min(capacity, b.tokens + (now - b.updatedAt) * refillPerMs);
            b.updatedAt = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private void sweep(long now) {
        if (now - lastSweep < IDLE_EVICT_MS) {
            return;
        }
        lastSweep = now;
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().updatedAt > IDLE_EVICT_MS) {
                it.remove();
            }
        }
    }
}
