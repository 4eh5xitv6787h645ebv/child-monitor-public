package de.rochefort.childmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #310/#423: the shared CRLF/header-injection guards used at the QR boundary AND on restore. */
class PairingValidationTest {

    @Test fun safeHostAcceptsRealAddresses() {
        for (h in listOf("192.168.0.5", "fd00::1234", "fe80::1%wlan0", "child-device.local", "host", "10_0_0_5")) {
            assertTrue(h, PairingValidation.isSafeHost(h))
        }
    }

    @Test fun safeHostRejectsInjectionAndJunk() {
        for (h in listOf("h\r\nX-Evil: v", "1.2.3.4 evil", "a/b", "user@host", "", null)) {
            assertFalse("$h", PairingValidation.isSafeHost(h))
        }
        assertFalse(PairingValidation.isSafeHost("x".repeat(256))) // over length cap
    }

    @Test fun safeTokenAcceptsCredentialsRejectsCrlf() {
        assertTrue(PairingValidation.isSafeToken("abcdef0123456789"))
        assertFalse(PairingValidation.isSafeToken("short"))            // < 8
        assertFalse(PairingValidation.isSafeToken("abcdefgh\r\nEvil")) // CRLF
        assertFalse(PairingValidation.isSafeToken(null))
    }

    @Test fun safeFingerprintMatchesSha256Shape() {
        val good = (1..32).joinToString(":") { "%02X".format(it) }
        assertTrue(PairingValidation.isSafeFingerprint(good))
        assertFalse(PairingValidation.isSafeFingerprint("AA:BB"))
        assertFalse(PairingValidation.isSafeFingerprint(null))
    }

    @Test fun validPortRange() {
        assertTrue(PairingValidation.isValidPort(10000))
        assertTrue(PairingValidation.isValidPort(1))
        assertTrue(PairingValidation.isValidPort(65535))
        assertFalse(PairingValidation.isValidPort(0))
        assertFalse(PairingValidation.isValidPort(-1))
        assertFalse(PairingValidation.isValidPort(65536))
    }
}
