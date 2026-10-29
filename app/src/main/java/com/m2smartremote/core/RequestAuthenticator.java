package com.m2smartremote.core;

import java.util.Map;

/**
 * Verifies the HMAC signature, timestamp window and nonce of every authenticated request.
 * This is also the CSRF defence: a foreign web page cannot produce a valid signature because
 * it cannot read the session key stored in this origin's storage.
 */
public final class RequestAuthenticator {
    /** Accepted difference between the phone's estimate of server time and server time. */
    public static final long MAX_SKEW_MS = 30_000;

    public static final class Result {
        public final Session session;
        public final ApiError error;

        Result(Session session, ApiError error) {
            this.session = session;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    private final Clock clock;
    private final SessionManager sessions;

    public RequestAuthenticator(Clock clock, SessionManager sessions) {
        this.clock = clock;
        this.sessions = sessions;
    }

    /** @param headers lower-cased header names, as NanoHTTPD provides them. */
    public Result verify(String method, String path, Map<String, String> headers, byte[] body) {
        String sid = headers.get(Protocol.HEADER_SESSION);
        String time = headers.get(Protocol.HEADER_TIME);
        String nonce = headers.get(Protocol.HEADER_NONCE);
        String sig = headers.get(Protocol.HEADER_SIGNATURE);
        if (!Crypto.isLowerHex(sid, Protocol.ID_HEX_LENGTH)
                || !Crypto.isLowerHex(nonce, Protocol.NONCE_HEX_LENGTH)
                || !Crypto.isLowerHex(sig, Protocol.MAC_HEX_LENGTH)
                || !isDecimal(time)) {
            return new Result(null, ApiError.UNAUTHORIZED);
        }
        Session[] holder = new Session[1];
        SessionManager.Lookup lookup = sessions.lookup(sid, holder);
        if (lookup == SessionManager.Lookup.EXPIRED) {
            return new Result(null, ApiError.SESSION_EXPIRED);
        }
        if (lookup != SessionManager.Lookup.OK) {
            // Unknown and revoked sessions look the same to the client: re-pair.
            return new Result(null, ApiError.SESSION_EXPIRED);
        }
        Session session = holder[0];
        byte[] expected = Protocol.requestSignature(session.key, method, path, time, nonce, body);
        if (!Crypto.constantTimeEquals(expected, Crypto.fromHex(sig))) {
            return new Result(null, ApiError.UNAUTHORIZED);
        }
        long now = clock.nowMillis();
        long ts = Long.parseLong(time);
        if (Math.abs(now - ts) > MAX_SKEW_MS) {
            return new Result(null, ApiError.STALE_TIMESTAMP);
        }
        if (!session.nonces.checkAndAdd(nonce, now)) {
            return new Result(null, ApiError.REPLAYED_REQUEST);
        }
        sessions.touch(session);
        return new Result(session, null);
    }

    private static boolean isDecimal(String s) {
        if (s == null || s.isEmpty() || s.length() > 15) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
