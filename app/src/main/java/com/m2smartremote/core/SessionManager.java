package com.m2smartremote.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tracks paired phones, enforces idle/absolute expiry, and supports revocation. */
public final class SessionManager {
    public static final int MAX_SESSIONS = 5;
    public static final long IDLE_TIMEOUT_MS = 12L * 60 * 60 * 1000;          // 12 hours
    public static final long ABSOLUTE_TIMEOUT_MS = 30L * 24 * 60 * 60 * 1000; // 30 days
    /** Replay window; must cover the request-timestamp window on both sides. */
    static final long NONCE_TTL_MS = 2 * RequestAuthenticator.MAX_SKEW_MS + 5_000;
    static final int NONCE_MAX = 4096;
    private static final long PERSIST_SEEN_EVERY_MS = 5 * 60 * 1000;

    public enum Lookup { OK, UNKNOWN, EXPIRED }

    public interface Listener {
        void onSessionsChanged();
    }

    private final Clock clock;
    private final SessionStore store;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public SessionManager(Clock clock, SessionStore store) {
        this.clock = clock;
        this.store = store;
        long now = clock.nowMillis();
        for (SessionStore.Record r : store.load()) {
            byte[] key = Crypto.fromHex(r.keyHex);
            if (key == null || key.length != 32 || !Crypto.isLowerHex(r.id, Protocol.ID_HEX_LENGTH)) {
                continue;
            }
            Session s = new Session(r.id, key, r.name, r.ip, r.createdAt, r.lastSeenAt, newNonces());
            if (!isExpired(s, now)) {
                sessions.put(s.id, s);
            }
        }
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public Session create(String id, byte[] key, String name, String ip) {
        long now = clock.nowMillis();
        Session s = new Session(id, key, name, ip, now, now, newNonces());
        synchronized (this) {
            purgeExpiredLocked(now);
            while (sessions.size() >= MAX_SESSIONS) {
                sessions.remove(leastRecentlySeenLocked().id);
            }
            sessions.put(id, s);
            persistLocked();
        }
        notifyListeners();
        return s;
    }

    /** Looks a session up, removing it if it has expired. {@code out[0]} receives the session. */
    public Lookup lookup(String id, Session[] out) {
        long now = clock.nowMillis();
        boolean removed = false;
        Lookup result;
        synchronized (this) {
            Session s = sessions.get(id);
            if (s == null) {
                result = Lookup.UNKNOWN;
            } else if (isExpired(s, now)) {
                sessions.remove(id);
                persistLocked();
                removed = true;
                result = Lookup.EXPIRED;
            } else {
                out[0] = s;
                result = Lookup.OK;
            }
        }
        if (removed) {
            notifyListeners();
        }
        return result;
    }

    public void touch(Session s) {
        long now = clock.nowMillis();
        s.lastSeenAt = now;
        if (now - s.lastPersistedSeenAt > PERSIST_SEEN_EVERY_MS) {
            synchronized (this) {
                s.lastPersistedSeenAt = now;
                persistLocked();
            }
        }
    }

    public boolean revoke(String id) {
        boolean removed;
        synchronized (this) {
            removed = sessions.remove(id) != null;
            if (removed) {
                persistLocked();
            }
        }
        if (removed) {
            notifyListeners();
        }
        return removed;
    }

    public void revokeAll() {
        synchronized (this) {
            sessions.clear();
            persistLocked();
        }
        notifyListeners();
    }

    public synchronized int count() {
        purgeExpiredLocked(clock.nowMillis());
        return sessions.size();
    }

    /** Snapshot ordered by most recently seen. */
    public synchronized List<Session> list() {
        purgeExpiredLocked(clock.nowMillis());
        List<Session> out = new ArrayList<>(sessions.values());
        Collections.sort(out, new Comparator<Session>() {
            @Override
            public int compare(Session a, Session b) {
                return Long.compare(b.lastSeenAt, a.lastSeenAt);
            }
        });
        return out;
    }

    public long expiresAt(Session s) {
        return Math.min(s.lastSeenAt + IDLE_TIMEOUT_MS, s.createdAt + ABSOLUTE_TIMEOUT_MS);
    }

    static boolean isExpired(Session s, long now) {
        return now - s.lastSeenAt > IDLE_TIMEOUT_MS || now - s.createdAt > ABSOLUTE_TIMEOUT_MS;
    }

    private void purgeExpiredLocked(long now) {
        boolean changed = false;
        for (Session s : new ArrayList<>(sessions.values())) {
            if (isExpired(s, now)) {
                sessions.remove(s.id);
                changed = true;
            }
        }
        if (changed) {
            persistLocked();
        }
    }

    private Session leastRecentlySeenLocked() {
        Session oldest = null;
        for (Session s : sessions.values()) {
            if (oldest == null || s.lastSeenAt < oldest.lastSeenAt) {
                oldest = s;
            }
        }
        return oldest;
    }

    private void persistLocked() {
        List<SessionStore.Record> records = new ArrayList<>();
        for (Session s : sessions.values()) {
            records.add(new SessionStore.Record(s.id, Crypto.toHex(s.key), s.name, s.ip,
                    s.createdAt, s.lastSeenAt));
        }
        store.save(records);
    }

    private static NonceCache newNonces() {
        return new NonceCache(NONCE_TTL_MS, NONCE_MAX);
    }

    private void notifyListeners() {
        for (Listener l : listeners) {
            l.onSessionsChanged();
        }
    }
}
