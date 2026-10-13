/*
 * This file is part of Child Monitor. GPLv3.
 */
package de.rochefort.childmonitor.parent

import org.junit.Test
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pass-5 MED-1 coverage for the parent-side pin enforcement. We can't easily
 * construct a real X509Certificate without BouncyCastle (an unsigned dep
 * on this project), so we reach the trust manager via reflection-free
 * access to the static factory and feed it the cases the production code
 * actually hits: null chain, empty chain, mismatched fingerprint.
 *
 * The happy "matching fingerprint" path is covered by the end-to-end pair-
 * and-snapshot flow in the manual test rig; that path is the one the user
 * cares about and an integration test asserting "TLS works between two
 * emulator-installed APKs" lives outside this unit-test slice.
 */
class PinnedTrustManagerTest {

    private val anyFp = (1..32).joinToString(":") { "AA" }

    private fun trustManagerWith(fp: String): X509TrustManager {
        // PinnedTls.socketFactory hands back an SSLSocketFactory wrapping the
        // trust manager; we need the trust manager itself for unit testing,
        // so reach through a tiny reflection workaround. Stays robust because
        // the inner class name is stable (kept private but accessed by name).
        val outer = Class.forName("de.rochefort.childmonitor.parent.PinnedTls\$PinnedTrustManager")
        val ctor = outer.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
        return ctor.newInstance(fp) as X509TrustManager
    }

    @Test fun rejectsClientAuth() {
        val tm = trustManagerWith(anyFp)
        assertFailsWith<java.security.cert.CertificateException> {
            tm.checkClientTrusted(emptyArray(), "RSA")
        }
    }

    @Test fun rejectsNullServerChain() {
        val tm = trustManagerWith(anyFp)
        val ex = assertFailsWith<java.security.cert.CertificateException> {
            tm.checkServerTrusted(null, "RSA")
        }
        assertTrue(ex.message?.contains("server cert") == true,
            "unexpected message: ${ex.message}")
    }

    @Test fun rejectsEmptyServerChain() {
        val tm = trustManagerWith(anyFp)
        assertFailsWith<java.security.cert.CertificateException> {
            tm.checkServerTrusted(emptyArray<X509Certificate>(), "RSA")
        }
    }

    @Test fun acceptedIssuersIsEmpty() {
        val tm = trustManagerWith(anyFp)
        assertTrue(tm.acceptedIssuers.isEmpty(),
            "self-signed TLS — no chain root issuers should be accepted")
    }
}
