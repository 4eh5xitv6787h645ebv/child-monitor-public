package de.rochefort.childmonitor.security

import de.rochefort.childmonitor.security.PairingChallengeGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [#232] The 6-digit pairing PIN must not be brute-forceable: a challenge must be burned
 * after a small number of failed attempts, regardless of how a caller varies deviceId/IP.
 */
class PairingChallengeGateTest {

    @Test
    fun noChallengeArmed_returnsNoChallenge() {
        assertEquals(Verdict.NO_CHALLENGE, PairingChallengeGate().verify("123456"))
    }

    @Test
    fun correctCode_succeeds_andBurnsChallenge() {
        val g = PairingChallengeGate(maxAttempts = 5) { 1000L }
        g.start("123456", expiresAtMs = 5000L)
        assertEquals(Verdict.OK, g.verify("123456"))
        // success burns the challenge: a replay finds nothing
        assertEquals(Verdict.NO_CHALLENGE, g.verify("123456"))
        assertFalse(g.hasActive())
    }

    @Test
    fun wrongCode_burnsChallengeAfterMaxAttempts_soItCannotBeBruteForced() {
        val g = PairingChallengeGate(maxAttempts = 3) { 1000L }
        g.start("123456", expiresAtMs = 9999L)
        assertEquals(Verdict.WRONG_CODE, g.verify("000000"))
        assertEquals(Verdict.WRONG_CODE, g.verify("111111"))
        assertEquals(Verdict.WRONG_CODE, g.verify("222222")) // 3rd failure burns it
        // Even the *correct* code is now refused — the attacker exhausted the challenge.
        assertEquals(Verdict.NO_CHALLENGE, g.verify("123456"))
        assertFalse(g.hasActive())
    }

    @Test
    fun maxAttemptsOfOne_burnsAfterFirstWrong() {
        val g = PairingChallengeGate(maxAttempts = 1) { 1000L }
        g.start("123456", expiresAtMs = 9999L)
        assertEquals(Verdict.WRONG_CODE, g.verify("000000"))
        assertEquals(Verdict.NO_CHALLENGE, g.verify("123456"))
    }

    @Test
    fun expiredChallenge_isRejectedAndCleared() {
        var t = 1000L
        val g = PairingChallengeGate(maxAttempts = 5) { t }
        g.start("123456", expiresAtMs = 2000L)
        t = 2001L
        assertEquals(Verdict.EXPIRED, g.verify("123456"))
        assertFalse(g.hasActive())
    }

    @Test
    fun hasActive_trueWhileArmedAndUnexpired() {
        var t = 1000L
        val g = PairingChallengeGate(maxAttempts = 5) { t }
        assertFalse(g.hasActive())
        g.start("123456", expiresAtMs = 2000L)
        assertTrue(g.hasActive())
        t = 3000L // past expiry
        assertFalse(g.hasActive())
    }

    @Test
    fun invalidate_dropsActiveChallenge() {
        val g = PairingChallengeGate(maxAttempts = 5) { 1000L }
        g.start("123456", expiresAtMs = 9999L)
        g.invalidate()
        assertEquals(Verdict.NO_CHALLENGE, g.verify("123456"))
    }

    @Test
    fun constantTimeEquals_matchesAndDiffers() {
        assertTrue(PairingChallengeGate.constantTimeEquals("123456", "123456"))
        assertFalse(PairingChallengeGate.constantTimeEquals("123456", "123457"))
        assertFalse(PairingChallengeGate.constantTimeEquals("123456", "12345"))
    }
}
