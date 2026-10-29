package com.m2smartremote.core;

/**
 * Wire-level key derivation and request signing. Mirrored exactly by assets/www/crypto.js.
 *
 * <p>The pairing code is never sent over the network. The phone proves knowledge of it with an
 * HMAC, and both sides derive the per-session key from it independently, so a passive observer of
 * the (plaintext HTTP) traffic never sees a reusable credential.
 */
public final class Protocol {
    public static final String HEADER_SESSION = "x-m2r-session";
    public static final String HEADER_TIME = "x-m2r-time";
    public static final String HEADER_NONCE = "x-m2r-nonce";
    public static final String HEADER_SIGNATURE = "x-m2r-signature";

    public static final int ID_HEX_LENGTH = 32;      // 16 random bytes
    public static final int NONCE_HEX_LENGTH = 32;   // 16 random bytes
    public static final int MAC_HEX_LENGTH = 64;     // HMAC-SHA256

    private Protocol() {
    }

    /** proof = HMAC(code, "m2r-pair-v1|" + clientNonce) */
    public static byte[] pairProof(String canonicalCode, String clientNonceHex) {
        return Crypto.hmacSha256(codeKey(canonicalCode), "m2r-pair-v1|" + clientNonceHex);
    }

    /** key = HMAC(code, "m2r-session-v1|" + sid + "|" + clientNonce + "|" + serverNonce) */
    public static byte[] sessionKey(String canonicalCode, String sessionId,
                                    String clientNonceHex, String serverNonceHex) {
        return Crypto.hmacSha256(codeKey(canonicalCode),
                "m2r-session-v1|" + sessionId + "|" + clientNonceHex + "|" + serverNonceHex);
    }

    /** Lets the phone confirm it is talking to the projector that displayed the code. */
    public static byte[] confirm(byte[] sessionKey, String sessionId) {
        return Crypto.hmacSha256(sessionKey, "m2r-confirm-v1|" + sessionId);
    }

    /**
     * sig = HMAC(key, "m2r-req-v1\n" + METHOD + "\n" + path + "\n" + time + "\n" + nonce + "\n"
     * + hex(sha256(body)))
     */
    public static byte[] requestSignature(byte[] sessionKey, String method, String path,
                                          String time, String nonceHex, byte[] body) {
        String bodyHash = Crypto.toHex(Crypto.sha256(body == null ? new byte[0] : body));
        String canonical = "m2r-req-v1\n" + method + "\n" + path + "\n" + time + "\n"
                + nonceHex + "\n" + bodyHash;
        return Crypto.hmacSha256(sessionKey, canonical);
    }

    private static byte[] codeKey(String canonicalCode) {
        return canonicalCode.getBytes(Crypto.UTF8);
    }
}
