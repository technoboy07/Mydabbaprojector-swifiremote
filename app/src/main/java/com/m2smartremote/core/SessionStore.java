package com.m2smartremote.core;

import java.util.List;

/** Persistence for paired sessions (SharedPreferences on Android, in-memory in tests). */
public interface SessionStore {
    final class Record {
        public final String id;
        public final String keyHex;
        public final String name;
        public final String ip;
        public final long createdAt;
        public final long lastSeenAt;

        public Record(String id, String keyHex, String name, String ip, long createdAt,
                      long lastSeenAt) {
            this.id = id;
            this.keyHex = keyHex;
            this.name = name;
            this.ip = ip;
            this.createdAt = createdAt;
            this.lastSeenAt = lastSeenAt;
        }
    }

    List<Record> load();

    void save(List<Record> records);
}
