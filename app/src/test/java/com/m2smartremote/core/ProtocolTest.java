package com.m2smartremote.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Fixtures were produced by assets/www/crypto.js, so these prove Java and JS agree. */
public class ProtocolTest {
    static final String CODE = "ABCD0123EFGH4567";
    static final String CLIENT_NONCE = "00112233445566778899aabbccddeeff";
    static final String SID = "0123456789abcdef0123456789abcdef";
    static final String SERVER_NONCE = "ffeeddccbbaa99887766554433221100";

    @Test
    public void pairProofMatchesJavascript() {
        assertEquals("97bb3aa528ddc049a7a99a8ab8195bcf7cedeefb7ad800c9298af0c7b6f4d416",
                Crypto.toHex(Protocol.pairProof(CODE, CLIENT_NONCE)));
    }

    @Test
    public void sessionKeyAndConfirmMatchJavascript() {
        byte[] key = Protocol.sessionKey(CODE, SID, CLIENT_NONCE, SERVER_NONCE);
        assertEquals("36adbd0500c093dc908ca0a65b59e30e18790892c51823617e51bcfd9507c366", Crypto.toHex(key));
        assertEquals("47b5bc293991e51a9c55eb621c3b6279f112c37d8ef087ffcdf9254b3c237039",
                Crypto.toHex(Protocol.confirm(key, SID)));
    }

    @Test
    public void requestSignatureMatchesJavascript() {
        byte[] key = Protocol.sessionKey(CODE, SID, CLIENT_NONCE, SERVER_NONCE);
        byte[] sig = Protocol.requestSignature(key, "POST", "/api/command", "1700000000000",
                CLIENT_NONCE, "{\"cmd\":\"UP\"}".getBytes(Crypto.UTF8));
        assertEquals("17f51a2f104636008038b0234021c93891b31a8e322fdc5967e43f6e264b9d2c", Crypto.toHex(sig));
    }

    @Test
    public void hmacMatchesRfc4231Case2() {
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                Crypto.toHex(Crypto.hmacSha256("Jefe".getBytes(Crypto.UTF8), "what do ya want for nothing?")));
    }

    @Test
    public void hexHelpers() {
        assertTrue(Crypto.isLowerHex("00ff", 4));
        assertFalse(Crypto.isLowerHex("00FF", 4));
        assertFalse(Crypto.isLowerHex("00f", 4));
        assertFalse(Crypto.isLowerHex(null, 4));
        assertNull(Crypto.fromHex("zz"));
        assertNull(Crypto.fromHex("abc"));
        assertTrue(Crypto.constantTimeEquals(new byte[]{1, 2}, new byte[]{1, 2}));
        assertFalse(Crypto.constantTimeEquals(new byte[]{1, 2}, new byte[]{1, 3}));
        assertFalse(Crypto.constantTimeEquals(new byte[]{1}, new byte[]{1, 2}));
        assertFalse(Crypto.constantTimeEquals(null, new byte[]{1}));
    }
}
