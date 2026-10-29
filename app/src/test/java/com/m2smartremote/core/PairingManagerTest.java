package com.m2smartremote.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.security.SecureRandom;

public class PairingManagerTest {
    private static final String NONCE = "00112233445566778899aabbccddeeff";
    private FakeClock clock;
    private PairingManager pm;

    @Before
    public void setUp() {
        clock = new FakeClock();
        pm = new PairingManager(clock, new SecureRandom(), 600_000);
        pm.setWindowOpen(true);
    }

    private String proof(String code) {
        return Crypto.toHex(Protocol.pairProof(code, NONCE));
    }

    @Test
    public void codeHas80BitsOfCrockfordBase32() {
        String code = pm.currentCode();
        assertEquals(16, code.length());
        for (char c : code.toCharArray()) {
            assertTrue(PairingManager.ALPHABET.indexOf(c) >= 0);
        }
    }

    @Test
    public void correctProofSucceedsAndCodeIsSingleUse() {
        String code = pm.currentCode();
        PairingManager.Result r = pm.verifyAndConsume(NONCE, proof(code));
        assertTrue(r.ok());
        assertEquals(code, r.code);
        assertNotEquals(code, pm.currentCode());
        // Replaying the same transcript fails because the code rotated.
        assertEquals(ApiError.PAIRING_FAILED, pm.verifyAndConsume(NONCE, proof(code)).error);
    }

    @Test
    public void wrongProofFails() {
        assertEquals(ApiError.PAIRING_FAILED, pm.verifyAndConsume(NONCE, proof("0000000000000000")).error);
    }

    @Test
    public void closedWindowRejectsEvenCorrectProof() {
        String code = pm.currentCode();
        pm.setWindowOpen(false);
        assertEquals(ApiError.PAIRING_CLOSED, pm.verifyAndConsume(NONCE, proof(code)).error);
    }

    @Test
    public void expiredCodeRotates() {
        String code = pm.currentCode();
        clock.advance(600_001);
        assertEquals(ApiError.PAIRING_FAILED, pm.verifyAndConsume(NONCE, proof(code)).error);
        assertNotEquals(code, pm.currentCode());
    }

    @Test
    public void tooManyFailuresRotateCode() {
        String code = pm.currentCode();
        for (int i = 0; i < PairingManager.MAX_FAILURES; i++) {
            pm.verifyAndConsume(NONCE, proof("0000000000000000"));
        }
        assertNotEquals(code, pm.currentCode());
    }

    @Test
    public void malformedInputIsInvalid() {
        assertEquals(ApiError.INVALID_INPUT, pm.verifyAndConsume("xyz", proof(pm.currentCode())).error);
        assertEquals(ApiError.INVALID_INPUT, pm.verifyAndConsume(NONCE, "nothex").error);
        assertEquals(ApiError.INVALID_INPUT, pm.verifyAndConsume(null, null).error);
    }

    @Test
    public void normalizeAndFormat() {
        assertEquals("ABCD0123EFGH4567", PairingManager.normalize("abcd-o123 efgh-4567"));
        assertEquals("1111000000000000", PairingManager.normalize("ILil-OOOO-0000-0000"));
        assertNull(PairingManager.normalize("ABCD"));
        assertNull(PairingManager.normalize("ABCD-0123-EFGH-456U"));
        assertEquals("ABCD-0123-EFGH-4567", PairingManager.format("ABCD0123EFGH4567"));
    }
}
