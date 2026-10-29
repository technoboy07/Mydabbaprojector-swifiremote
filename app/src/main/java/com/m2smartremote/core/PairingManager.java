package com.m2smartremote.core;

import java.security.SecureRandom;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the one-time pairing code shown on the projector screen.
 *
 * <ul>
 *   <li>16 Crockford-base32 characters = 80 bits from SecureRandom.</li>
 *   <li>Accepted only while the pairing screen is visible ({@link #setWindowOpen}).</li>
 *   <li>Single use: rotated after every successful pairing.</li>
 *   <li>Rotated after {@link #MAX_FAILURES} wrong attempts and after {@code codeTtlMs}.</li>
 * </ul>
 */
public final class PairingManager {
    public static final int CODE_LENGTH = 16;
    public static final int MAX_FAILURES = 5;
    /** Crockford base32: no I, L, O, U, so codes are easy to read off a projector screen. */
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    public interface Listener {
        void onPairingCodeChanged();
    }

    public static final class Result {
        public final ApiError error;
        /** Canonical code the proof matched; only set on success. */
        public final String code;

        private Result(ApiError error, String code) {
            this.error = error;
            this.code = code;
        }

        public boolean ok() {
            return error == null;
        }
    }

    private final Clock clock;
    private final SecureRandom random;
    private final long codeTtlMs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private String code;
    private long expiresAt;
    private int failures;
    private boolean windowOpen;

    public PairingManager(Clock clock, SecureRandom random, long codeTtlMs) {
        this.clock = clock;
        this.random = random;
        this.codeTtlMs = codeTtlMs;
        rotateLocked();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Current canonical code, rotating first if it has expired. */
    public String currentCode() {
        boolean rotated;
        String c;
        synchronized (this) {
            rotated = rotateIfExpiredLocked();
            c = code;
        }
        if (rotated) {
            notifyListeners();
        }
        return c;
    }

    public synchronized long expiresAt() {
        return expiresAt;
    }

    public synchronized boolean isWindowOpen() {
        return windowOpen;
    }

    public void setWindowOpen(boolean open) {
        synchronized (this) {
            windowOpen = open;
        }
    }

    public void rotate() {
        synchronized (this) {
            rotateLocked();
        }
        notifyListeners();
    }

    /**
     * Verifies {@code proofHex == HMAC(code, "m2r-pair-v1|" + clientNonce)} and consumes the code.
     */
    public Result verifyAndConsume(String clientNonceHex, String proofHex) {
        if (!Crypto.isLowerHex(clientNonceHex, Protocol.NONCE_HEX_LENGTH)
                || !Crypto.isLowerHex(proofHex, Protocol.MAC_HEX_LENGTH)) {
            return new Result(ApiError.INVALID_INPUT, null);
        }
        boolean rotated = false;
        Result result;
        synchronized (this) {
            if (!windowOpen) {
                return new Result(ApiError.PAIRING_CLOSED, null);
            }
            rotated = rotateIfExpiredLocked();
            byte[] expected = Protocol.pairProof(code, clientNonceHex);
            if (Crypto.constantTimeEquals(expected, Crypto.fromHex(proofHex))) {
                result = new Result(null, code);
                rotateLocked();
                rotated = true;
            } else {
                failures++;
                if (failures >= MAX_FAILURES) {
                    rotateLocked();
                    rotated = true;
                }
                result = new Result(ApiError.PAIRING_FAILED, null);
            }
        }
        if (rotated) {
            notifyListeners();
        }
        return result;
    }

    /** "ABCD-EFGH-JKMN-PQRS" for display. */
    public static String format(String canonical) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < canonical.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(canonical.charAt(i));
        }
        return sb.toString();
    }

    /**
     * Normalizes user input the same way the phone does: uppercase, strip separators, and map the
     * look-alikes O to 0 and I/L to 1. Returns null if the result is not a well-formed code.
     */
    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = Character.toUpperCase(input.charAt(i));
            if (c == '-' || c == ' ') {
                continue;
            }
            if (c == 'O') {
                c = '0';
            } else if (c == 'I' || c == 'L') {
                c = '1';
            }
            if (ALPHABET.indexOf(c) < 0) {
                return null;
            }
            sb.append(c);
        }
        return sb.length() == CODE_LENGTH ? sb.toString() : null;
    }

    private boolean rotateIfExpiredLocked() {
        if (clock.nowMillis() >= expiresAt) {
            rotateLocked();
            return true;
        }
        return false;
    }

    private void rotateLocked() {
        char[] out = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        code = new String(out);
        expiresAt = clock.nowMillis() + codeTtlMs;
        failures = 0;
    }

    private void notifyListeners() {
        for (Listener l : listeners) {
            l.onPairingCodeChanged();
        }
    }
}
