package de.rochefort.childmonitor.security

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

/** AndroidKeyStore also contains credential-wrapping keys; TLS must present only its QR identity. */
internal class ServerIdentityKeyManager(
    private val delegate: X509KeyManager,
    private val identityAlias: String,
) : X509ExtendedKeyManager() {
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        delegate.getServerAliases(keyType, issuers)?.filter { it == identityAlias }
            ?.takeIf { it.isNotEmpty() }?.toTypedArray()

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
        getServerAliases(keyType, issuers)?.firstOrNull()

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        getServerAliases(keyType, issuers)?.firstOrNull()

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == identityAlias) delegate.getCertificateChain(alias) else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == identityAlias) delegate.getPrivateKey(alias) else null

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null
}
