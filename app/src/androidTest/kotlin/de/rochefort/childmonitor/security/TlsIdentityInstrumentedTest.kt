package de.rochefort.childmonitor.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.rochefort.childmonitor.parent.PinnedTls
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.security.auth.x500.X500Principal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TlsIdentityInstrumentedTest {
    @Test fun unresponsiveAndroidTlsPeerTimesOutWithoutLeakingSocket() {
        java.net.ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { plain ->
                server.accept().use {
                    plain.soTimeout = 200
                    val attempt = FutureTask {
                        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                            PinnedTls.upgrade(plain, "127.0.0.1", server.localPort, "0".repeat(64))
                        }
                    }
                    Thread(attempt, "android-tls-timeout-test").apply { isDaemon = true }.start()
                    attempt.get(3, TimeUnit.SECONDS)
                    assertTrue(plain.isClosed)
                }
            }
        }
    }

    @Test fun unrelatedKeystoreKeysCannotChangeCertificatePresentedToParents() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = listOf("a-tls-test-foreign", "z-tls-test-foreign")
        try {
            aliases.forEach { alias ->
                val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=unrelated-test-key"))
                    .build()
                KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                    initialize(spec); generateKeyPair()
                }
            }
            val identity = TlsIdentity.getInstance(context)
            assertTrue(identity.isReady())
            val pin = identity.fingerprintSha256()!!
            // Reload through the same path used after process restart; pairing pins must persist.
            TlsIdentity::class.java.getDeclaredMethod("loadOrCreate").apply { isAccessible = true }.invoke(identity)
            assertEquals(pin, identity.fingerprintSha256())
            identity.newServerSocketFactory()!!.createServerSocket(0).use { server ->
                server.soTimeout = 5_000
                val sender = FutureTask {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5_000
                        socket.startHandshake()
                        socket.getOutputStream().write(42)
                    }
                }
                Thread(sender, "tls-identity-test").apply { isDaemon = true }.start()
                Socket("127.0.0.1", server.localPort).use { plain ->
                    plain.soTimeout = 5_000
                    PinnedTls.upgrade(plain, "127.0.0.1", server.localPort, pin).use { tls ->
                        assertEquals(5_000, tls.soTimeout)
                        assertEquals(42, tls.getInputStream().read())
                        assertEquals(pin, TlsIdentity.fingerprintOf(tls.session.peerCertificates.first() as java.security.cert.X509Certificate))
                    }
                }
                sender.get(6, TimeUnit.SECONDS)
            }
        } finally { aliases.forEach { store.deleteEntry(it) } }
    }
}
