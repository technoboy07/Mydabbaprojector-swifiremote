package com.m2smartremote.core;

/** One paired phone. The key never leaves the projector after pairing. */
public final class Session {
    public final String id;
    final byte[] key;
    public final String name;
    public final String ip;
    public final long createdAt;
    volatile long lastSeenAt;
    volatile long lastPersistedSeenAt;
    final NonceCache nonces;

    Session(String id, byte[] key, String name, String ip, long createdAt, long lastSeenAt,
            NonceCache nonces) {
        this.id = id;
        this.key = key;
        this.name = name;
        this.ip = ip;
        this.createdAt = createdAt;
        this.lastSeenAt = lastSeenAt;
        this.lastPersistedSeenAt = lastSeenAt;
        this.nonces = nonces;
    }

    public long lastSeenAt() {
        return lastSeenAt;
    }
}
