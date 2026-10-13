package de.rochefort.childmonitor.security

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509KeyManager
import org.junit.Assert.*
import org.junit.Test

class ServerIdentityKeyManagerTest {
    private val key = object : PrivateKey {
        override fun getAlgorithm() = "RSA"
        override fun getFormat(): String? = null
        override fun getEncoded(): ByteArray? = null
    }
    private val delegate = object : X509KeyManager {
        override fun getServerAliases(type: String?, issuers: Array<out Principal>?): Array<String>? =
            if (type == "RSA" && issuers == null) arrayOf("credential-wrapper", "tls-identity") else null
        override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = "credential-wrapper"
        override fun getPrivateKey(alias: String?) = key
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = null
        override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = arrayOf("credential-wrapper")
        override fun chooseClientAlias(type: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "credential-wrapper"
    }
    private val manager = ServerIdentityKeyManager(delegate, "tls-identity")

    @Test fun socketAndEngineSelectOnlyQrIdentityEvenIfForeignKeyIsFirst() {
        assertArrayEquals(arrayOf("tls-identity"), manager.getServerAliases("RSA", null))
        assertEquals("tls-identity", manager.chooseServerAlias("RSA", null, null))
        assertEquals("tls-identity", manager.chooseEngineServerAlias("RSA", null, null))
        assertSame(key, manager.getPrivateKey("tls-identity"))
        assertNull(manager.getPrivateKey("credential-wrapper"))
        assertNull(manager.getCertificateChain("credential-wrapper"))
    }

    @Test fun unsupportedKeyTypeAndIssuerDoNotFallBackToAnotherKey() {
        assertNull(manager.chooseServerAlias("EC", null, null))
        assertNull(manager.chooseEngineServerAlias("RSA", arrayOf(Principal { "other issuer" }), null))
        assertNull(ServerIdentityKeyManager(delegate, "missing-identity").chooseServerAlias("RSA", null, null))
    }

    @Test fun serverIdentityNeverOffersAppCredentialKeysForClientAuthentication() {
        assertNull(manager.getClientAliases("RSA", null))
        assertNull(manager.chooseClientAlias(arrayOf("RSA"), null, null))
        assertNull(manager.chooseEngineClientAlias(arrayOf("RSA"), null, null))
    }
}
