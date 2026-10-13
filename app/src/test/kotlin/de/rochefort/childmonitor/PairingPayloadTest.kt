/*
 * This file is part of Child Monitor. GPLv3.
 */
package de.rochefort.childmonitor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass-5 MED-1 coverage for the QR-decoding parser. Every test asserts a
 * concrete attacker-controlled QR shape produces a known parser outcome —
 * exercising the SAFE_TOKEN / SAFE_FP / hp-range guards added in #129 +
 * #193. The parent's downstream pin/token code trusts these regex'd values
 * directly, so a regression here is what would actually let a malicious
 * QR header-inject into the talkback WebSocket upgrade.
 */
class PairingPayloadTest {

    private val validFp = (1..32).joinToString(":") { "AB" }
    private val validToken = "abcdefghIJKLMNOP12345678._-" // gitleaks:allow -- deterministic test fixture

    @Test fun roundTripsAllFields() {
        val original = PairingPayload(
            deviceId = "child-1",
            name = "Nursery",
            host = "192.168.1.42",
            port = 10000,
            token = validToken,
            fingerprint = validFp,
            httpsPort = 11443,
            audioTlsPort = 10443
        )
        val encoded = original.encode()
        val parsed = PairingPayload.parse(encoded)
        assertNotNull(parsed)
        assertEquals("child-1", parsed.deviceId)
        assertEquals("Nursery", parsed.name)
        assertEquals("192.168.1.42", parsed.host)
        assertEquals(10000, parsed.port)
        assertEquals(validToken, parsed.token)
        assertEquals(validFp, parsed.fingerprint)
        assertEquals(11443, parsed.httpsPort)
        assertEquals(10443, parsed.audioTlsPort)
        assertEquals(2, parsed.version)
        assertTrue(parsed.isDurableSecure())
    }

    @Test fun rejectsWrongScheme() {
        assertNull(PairingPayload.parse("http://example.com/?id=x&host=y"))
        assertNull(PairingPayload.parse("javascript:alert(1)"))
        assertNull(PairingPayload.parse(""))
    }

    @Test fun rejectsMissingRequiredFields() {
        assertNull(PairingPayload.parse("childmonitor://discover?host=1.2.3.4"))
        assertNull(PairingPayload.parse("childmonitor://discover?id=&host=1.2.3.4"))
        assertNull(PairingPayload.parse("childmonitor://discover?id=x&host="))
    }

    @Test fun dropsInvalidTokenButKeepsRest() {
        // Token with CRLF — must be dropped (header-injection vector).
        // Pad to >=8 chars before the CRLF so the rejection is specifically
        // because CRLF doesn't match SAFE_TOKEN, not because it's too short.
        val raw = "childmonitor://discover?id=x&name=N&host=h&port=10000&token=abcdefgh%0D%0AHost%3A%20evil"
        val parsed = PairingPayload.parse(raw)
        assertNotNull(parsed)
        assertNull(parsed.token, "token with CRLF must be stripped")
    }

    @Test fun rejectsHostWithCrlf() {
        // #423: a host with CRLF would header-inject into the raw WS upgrade
        // ("Host: $address...\r\n"). It must reject the WHOLE payload (fail-closed),
        // unlike the token which is merely dropped.
        val raw = "childmonitor://discover?id=x&name=N&host=1.2.3.4%0D%0AX-Evil%3A%20v&port=10000"
        assertNull(PairingPayload.parse(raw))
    }

    @Test fun rejectsHostWithControlOrInjectionChars() {
        // Space, slash and '@' are not valid in a bare host/authority and could enable
        // request-line or userinfo trickery — all must reject the payload.
        assertNull(PairingPayload.parse("childmonitor://discover?id=x&host=1.2.3.4%20evil&port=10000"))
        assertNull(PairingPayload.parse("childmonitor://discover?id=x&host=evil%2Fpath&port=10000"))
        assertNull(PairingPayload.parse("childmonitor://discover?id=x&host=user%40host&port=10000"))
    }

    @Test fun acceptsValidIpv4Ipv6AndHostnameHosts() {
        for (h in listOf("192.168.0.168", "fd00::1234", "child-device.local", "10_0_0_5", "host")) {
            val raw = "childmonitor://discover?id=x&name=N&host=$h&port=10000"
            val parsed = PairingPayload.parse(raw)
            assertNotNull(parsed, "host '$h' should be accepted")
            assertEquals(h, parsed.host)
        }
    }

    @Test fun roundTripsSpacesInName() {
        // Locks the URLEncoder + URLDecoder space-vs-plus behaviour. A
        // family-friendly device name like "Baby's Room +1" round-trips
        // through encode -> parse without mangling.
        val original = PairingPayload(
            deviceId = "child-1",
            name = "Baby's Room +1",
            host = "1.2.3.4",
            port = 10000
        )
        val parsed = PairingPayload.parse(original.encode())
        assertNotNull(parsed)
        assertEquals("Baby's Room +1", parsed.name)
    }

    @Test fun stripsUrlFragment() {
        // Anything after # belongs to the fragment, not the query — match
        // android.net.Uri behaviour so a stray #anchor in a hand-crafted QR
        // doesn't leak into unvalidated host/name fields.
        val raw = "childmonitor://discover?id=x&host=h&port=10000#someAnchor"
        val parsed = PairingPayload.parse(raw)
        assertNotNull(parsed)
        assertEquals("h", parsed.host)
    }

    @Test fun firstOccurrenceWinsForDuplicateKey() {
        // An attacker who appends &id=evil to override the legitimate id MUST
        // be ignored — Uri.getQueryParameter returns the first match.
        val raw = "childmonitor://discover?id=legit&id=evil&host=h"
        val parsed = PairingPayload.parse(raw)
        assertNotNull(parsed)
        assertEquals("legit", parsed.deviceId)
    }

    @Test fun dropsTooShortToken() {
        val raw = "childmonitor://discover?id=x&host=h&token=short"
        val parsed = PairingPayload.parse(raw)
        assertNotNull(parsed)
        assertNull(parsed.token)
    }

    @Test fun dropsInvalidFingerprint() {
        // Wrong length, lowercase-only mixed in still validated, missing colons.
        listOf(
            "AB:CD:EF",  // too short
            "ZZ:" + (1..31).joinToString(":") { "AB" },  // non-hex
            (1..32).joinToString("") { "AB" }            // no colons
        ).forEach { fp ->
            val raw = "childmonitor://discover?id=x&host=h&fp=$fp"
            val parsed = PairingPayload.parse(raw)
            assertNotNull(parsed)
            assertNull(parsed.fingerprint, "fp '$fp' must be rejected")
        }
    }

    @Test fun normalizesLowercaseFingerprintToUpper() {
        val lower = (1..32).joinToString(":") { "ab" }
        val raw = "childmonitor://discover?id=x&host=h&fp=$lower"
        val parsed = PairingPayload.parse(raw)
        assertNotNull(parsed?.fingerprint)
        assertEquals(parsed!!.fingerprint!!.uppercase(), parsed.fingerprint)
    }

    @Test fun rejectsOutOfRangeHttpsPort() {
        listOf("0", "65536", "999999", "abc", "-1").forEach { hp ->
            val raw = "childmonitor://discover?id=x&host=h&hp=$hp"
            val parsed = PairingPayload.parse(raw)
            assertNotNull(parsed)
            assertNull(parsed.httpsPort, "hp '$hp' must be rejected")
        }
    }

    @Test fun rejectsOutOfRangeAudioTlsPort() {
        listOf("0", "65536", "999999", "abc", "-1").forEach { ap ->
            val parsed = PairingPayload.parse("childmonitor://discover?id=x&host=h&ap=$ap")
            assertNotNull(parsed)
            assertNull(parsed.audioTlsPort, "ap '$ap' must be rejected")
        }
    }

    @Test fun acceptsLegacyV1QrWithoutTlsFields() {
        val raw = "childmonitor://discover?id=x&host=h&port=10000&v=1"
        val parsed = PairingPayload.parse(raw)
        assertNotNull(parsed)
        assertNull(parsed.token)
        assertNull(parsed.fingerprint)
        assertNull(parsed.httpsPort)
        assertNull(parsed.audioTlsPort)
        assertEquals(1, parsed.version)
        assertTrue(!parsed.isDurableSecure())
    }

    @Test fun encodeOmitsNullFields() {
        val payload = PairingPayload(deviceId = "x", name = "N", host = "h", port = 10000)
        val encoded = payload.encode()
        assertTrue(!encoded.contains("token="), "encoded='$encoded' should omit token=")
        assertTrue(!encoded.contains("fp="), "encoded='$encoded' should omit fp=")
        assertTrue(!encoded.contains("hp="), "encoded='$encoded' should omit hp=")
        assertTrue(!encoded.contains("ap="), "encoded='$encoded' should omit ap=")
    }
}
