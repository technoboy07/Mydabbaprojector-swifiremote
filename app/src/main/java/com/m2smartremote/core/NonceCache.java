package com.m2smartremote.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Remembers recently seen request nonces so a captured request cannot be replayed. */
public final class NonceCache {
    private final long ttlMs;
    private final int maxEntries;
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

    public NonceCache(long ttlMs, int maxEntries) {
        this.ttlMs = ttlMs;
        this.maxEntries = maxEntries;
    }

    /** Returns false if the nonce was already used within the TTL. */
    public synchronized boolean checkAndAdd(String nonce, long now) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > ttlMs) {
                it.remove();
            } else {
                break; // insertion order == time order
            }
        }
        if (seen.containsKey(nonce)) {
            return false;
        }
        if (seen.size() >= maxEntries) {
            Iterator<String> eldest = seen.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        seen.put(nonce, now);
        return true;
    }

    public synchronized int size() {
        return seen.size();
    }
}
