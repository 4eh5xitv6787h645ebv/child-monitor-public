/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package de.rochefort.childmonitor.parent

import de.rochefort.childmonitor.security.TlsIdentity
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Parent-side TLS pinning. Builds an [SSLSocketFactory] that:
 *  - skips the system trust store entirely (the child's self-signed cert is not
 *    signed by any public CA), and
 *  - rejects every server cert whose SHA-256 fingerprint doesn't match the
 *    [expectedFingerprint] the parent recorded at QR-pairing time.
 *
 * This is trust-on-first-use pinning: the QR scan is the trust transfer event,
 * everything after that is "exact match or fail." Cert rotation requires the
 * parent to rescan a fresh QR.
 *
 * Hostname verification is disabled because the child's cert SAN is `CN=
 * childmonitor-local` (no DNS / IP entries — they'd need a SAN-extension
 * extension which the AndroidKeyStore self-issuance API doesn't expose
 * directly). The pin check supersedes hostname matching: a leaf cert whose
 * SHA-256 matches the QR-advertised fingerprint can only be the keypair the
 * child generated, regardless of what hostname the parent connected to.
 */
object PinnedTls {

    /** Build a pinned [SSLSocketFactory] that fails the handshake unless the
     *  server's leaf cert SHA-256 equals [expectedFingerprint]. */
    fun socketFactory(expectedFingerprint: String): SSLSocketFactory {
        val tm = PinnedTrustManager(expectedFingerprint)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        return ctx.socketFactory
    }

    /** Apply pinning to an [HttpsURLConnection] (sets socket factory + a
     *  pass-through hostname verifier because we authenticate via the pin). */
    fun apply(conn: HttpsURLConnection, expectedFingerprint: String) {
        conn.sslSocketFactory = socketFactory(expectedFingerprint)
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
    }

    /** Upgrade a raw [Socket] (open TCP) to TLS with the same pinning. The
     *  returned [SSLSocket] has its handshake started — verify it returns
     *  without throwing before sending application data. */
    fun upgrade(plainSocket: Socket, host: String, port: Int, expectedFingerprint: String): SSLSocket {
        val readTimeout = plainSocket.soTimeout
        var tls: SSLSocket? = null
        try {
            tls = socketFactory(expectedFingerprint)
                .createSocket(plainSocket, host, port, /* autoClose= */ true) as SSLSocket
            // Bound negotiation even before the caller configures its audio/WS read timeout.
            // A TCP peer that accepts but never responds must not stall reconnect forever.
            tls.soTimeout = if (readTimeout > 0) readTimeout else HANDSHAKE_TIMEOUT_MS
            tls.startHandshake()
            tls.soTimeout = readTimeout
            return tls
        } catch (failure: Exception) {
            try { (tls ?: plainSocket).close() } catch (_: Exception) {}
            throw failure
        }
    }

    private const val HANDSHAKE_TIMEOUT_MS = 3_000

    private class PinnedTrustManager(expected: String) : X509TrustManager {
        // Uppercased once so the inner-loop comparison is a single ==.
        private val expectedUpper = expected.uppercase()

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // Server-only TLS — we never authenticate clients with a cert.
            throw CertificateException("client auth not supported")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("no server cert presented")
            val actual = TlsIdentity.fingerprintOf(leaf)
                ?: throw CertificateException("cannot compute SHA-256 of leaf cert")
            if (actual.uppercase() != expectedUpper) {
                throw CertificateException(
                    "TLS pin mismatch: expected $expectedUpper, got $actual"
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
