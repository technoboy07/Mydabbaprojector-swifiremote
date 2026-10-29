package com.m2smartremote.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class SessionAndAuthTest {
    private FakeClock clock;
    private MemoryStore store;
    private SessionManager sessions;
    private RequestAuthenticator auth;
    private String sid;
    private byte[] key;

    @Before
    public void setUp() {
        clock = new FakeClock();
        store = new MemoryStore();
        sessions = new SessionManager(clock, store);
        auth = new RequestAuthenticator(clock, sessions);
        sid = Crypto.toHex(Crypto.randomBytes(16));
        key = Crypto.randomBytes(32);
        sessions.create(sid, key, "Pixel", "192.168.1.20");
    }

    private Map<String, String> sign(String method, String path, String body, long time, String nonce,
                                     byte[] k, String s) {
        Map<String, String> h = new HashMap<>();
        h.put(Protocol.HEADER_SESSION, s);
        h.put(Protocol.HEADER_TIME, Long.toString(time));
        h.put(Protocol.HEADER_NONCE, nonce);
        h.put(Protocol.HEADER_SIGNATURE, Crypto.toHex(Protocol.requestSignature(k, method, path,
                Long.toString(time), nonce, body.getBytes(Crypto.UTF8))));
        return h;
    }

    private Map<String, String> sign(String body) {
        return sign("POST", "/api/command", body, clock.now, Crypto.toHex(Crypto.randomBytes(16)), key, sid);
    }

    @Test
    public void validRequestPasses() {
        String body = "{\"cmd\":\"UP\"}";
        RequestAuthenticator.Result r = auth.verify("POST", "/api/command", sign(body), body.getBytes(Crypto.UTF8));
        assertTrue(r.ok());
        assertEquals(sid, r.session.id);
    }

    @Test
    public void missingOrMalformedHeadersAreUnauthorized() {
        assertEquals(ApiError.UNAUTHORIZED, auth.verify("POST", "/api/command", new HashMap<String, String>(), new byte[0]).error);
        Map<String, String> h = sign("{}");
        h.put(Protocol.HEADER_TIME, "-5");
        assertEquals(ApiError.UNAUTHORIZED, auth.verify("POST", "/api/command", h, "{}".getBytes(Crypto.UTF8)).error);
    }

    @Test
    public void tamperedBodyPathOrMethodIsRejected() {
        String body = "{\"cmd\":\"UP\"}";
        Map<String, String> h = sign(body);
        assertEquals(ApiError.UNAUTHORIZED, auth.verify("POST", "/api/command", h, "{\"cmd\":\"HOME\"}".getBytes(Crypto.UTF8)).error);
        assertEquals(ApiError.UNAUTHORIZED, auth.verify("POST", "/api/server/stop", h, body.getBytes(Crypto.UTF8)).error);
        assertEquals(ApiError.UNAUTHORIZED, auth.verify("GET", "/api/command", h, body.getBytes(Crypto.UTF8)).error);
    }

    @Test
    public void wrongKeyIsRejected() {
        Map<String, String> h = sign("POST", "/api/command", "{}", clock.now,
                Crypto.toHex(Crypto.randomBytes(16)), Crypto.randomBytes(32), sid);
        assertEquals(ApiError.UNAUTHORIZED, auth.verify("POST", "/api/command", h, "{}".getBytes(Crypto.UTF8)).error);
    }

    @Test
    public void unknownSessionMustRepair() {
        Map<String, String> h = sign("POST", "/api/command", "{}", clock.now,
                Crypto.toHex(Crypto.randomBytes(16)), key, Crypto.toHex(Crypto.randomBytes(16)));
        assertEquals(ApiError.SESSION_EXPIRED, auth.verify("POST", "/api/command", h, "{}".getBytes(Crypto.UTF8)).error);
    }

    @Test
    public void replayIsRejected() {
        Map<String, String> h = sign("{}");
        assertTrue(auth.verify("POST", "/api/command", h, "{}".getBytes(Crypto.UTF8)).ok());
        assertEquals(ApiError.REPLAYED_REQUEST, auth.verify("POST", "/api/command", h, "{}".getBytes(Crypto.UTF8)).error);
    }

    @Test
    public void staleTimestampIsRejected() {
        Map<String, String> h = sign("POST", "/api/command", "{}", clock.now - RequestAuthenticator.MAX_SKEW_MS - 1,
                Crypto.toHex(Crypto.randomBytes(16)), key, sid);
        assertEquals(ApiError.STALE_TIMESTAMP, auth.verify("POST", "/api/command", h, "{}".getBytes(Crypto.UTF8)).error);
        Map<String, String> future = sign("POST", "/api/command", "{}", clock.now + RequestAuthenticator.MAX_SKEW_MS + 1,
                Crypto.toHex(Crypto.randomBytes(16)), key, sid);
        assertEquals(ApiError.STALE_TIMESTAMP, auth.verify("POST", "/api/command", future, "{}".getBytes(Crypto.UTF8)).error);
    }

    @Test
    public void revokedSessionIsRejected() {
        assertTrue(sessions.revoke(sid));
        assertEquals(ApiError.SESSION_EXPIRED, auth.verify("POST", "/api/command", sign("{}"), "{}".getBytes(Crypto.UTF8)).error);
    }

    @Test
    public void revokeAllClearsEverything() {
        sessions.revokeAll();
        assertEquals(0, sessions.count());
        assertTrue(store.records.isEmpty());
    }

    @Test
    public void idleSessionExpires() {
        clock.advance(SessionManager.IDLE_TIMEOUT_MS + 1);
        assertEquals(ApiError.SESSION_EXPIRED, auth.verify("POST", "/api/command", sign("{}"), "{}".getBytes(Crypto.UTF8)).error);
        assertEquals(0, sessions.count());
    }

    @Test
    public void activeSessionStillHitsAbsoluteLimit() {
        long step = SessionManager.IDLE_TIMEOUT_MS / 2;
        long elapsed = 0;
        while (elapsed <= SessionManager.ABSOLUTE_TIMEOUT_MS) {
            clock.advance(step);
            elapsed += step;
            if (elapsed <= SessionManager.ABSOLUTE_TIMEOUT_MS) {
                assertTrue(auth.verify("POST", "/api/command", sign("{}"), "{}".getBytes(Crypto.UTF8)).ok());
            }
        }
        assertEquals(ApiError.SESSION_EXPIRED, auth.verify("POST", "/api/command", sign("{}"), "{}".getBytes(Crypto.UTF8)).error);
    }

    @Test
    public void sessionsPersistAndReload() {
        SessionManager reloaded = new SessionManager(clock, store);
        assertEquals(1, reloaded.count());
        RequestAuthenticator auth2 = new RequestAuthenticator(clock, reloaded);
        assertTrue(auth2.verify("POST", "/api/command", sign("{}"), "{}".getBytes(Crypto.UTF8)).ok());
    }

    @Test
    public void corruptStoredSessionsAreIgnored() {
        store.records.add(new SessionStore.Record("bad", "zz", "x", "y", clock.now, clock.now));
        assertEquals(1, new SessionManager(clock, store).count());
    }

    @Test
    public void oldestSessionIsEvictedAtCapacity() {
        for (int i = 0; i < SessionManager.MAX_SESSIONS; i++) {
            clock.advance(1000);
            sessions.create(Crypto.toHex(Crypto.randomBytes(16)), Crypto.randomBytes(32), "p" + i, "10.0.0." + i);
        }
        assertEquals(SessionManager.MAX_SESSIONS, sessions.count());
        Session[] out = new Session[1];
        assertEquals(SessionManager.Lookup.UNKNOWN, sessions.lookup(sid, out));
        assertNull(out[0]);
    }

    @Test
    public void nonceCacheExpiresAndBounds() {
        NonceCache c = new NonceCache(1000, 3);
        assertTrue(c.checkAndAdd("a", 0));
        assertFalse(c.checkAndAdd("a", 500));
        assertTrue(c.checkAndAdd("a", 1501));
        c.checkAndAdd("b", 1502);
        c.checkAndAdd("c", 1503);
        c.checkAndAdd("d", 1504);
        assertEquals(3, c.size());
    }
}
