package de.rochefort.childmonitor.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

/**
 * Manages the self-signed TLS identity for the local web console and streaming endpoints.
 * Implements CM-SEC-003 (encrypt local transport) and CM-SEC-008 (TOFU/pinning).
 *
 * The keypair is generated inside AndroidKeyStore (hardware-backed when
 * available) with an auto-issued self-signed X.509 cert. The [KeyManagerFactory] is built
 * DIRECTLY from the AndroidKeyStore so NanoHTTPD's `makeSecure` can consume it — the private
 * key is never exported (it can't be: a hardware-backed key has no encoding, so the old
 * "copy into a PKCS12 wrapper" path threw "Cannot wrap key, null encoding" on real devices and
 * left TLS disabled — see #350).
 *
 * The SHA-256 fingerprint is surfaced to parent devices for trust-on-first-use (TOFU)
 * pinning — parents store the fingerprint at QR-pairing time and refuse any later
 * TLS handshake whose leaf cert doesn't match.
 *
 * Review-pass-1 #2: process singleton via [getInstance]. The previous constructor
 * was public; MonitorService and ChildPairingActivity each instantiated their own,
 * causing two threads to race through `loadOrCreate()` on first run and overwrite
 * the keystore + PKCS12 file, producing a TOCTOU where the QR-advertised
 * fingerprint differed from the one the running server presented.
 */
class TlsIdentity private constructor(context: Context) {

    // #350: best-effort cleanup of the orphaned PKCS12 file written by the old (broken) export path.
    private val legacyKeystoreFile = File(context.filesDir, LEGACY_KEYSTORE_FILE)

    // #350: this is the AndroidKeyStore itself (holding ANDROID_ALIAS), used directly by the KMF.
    private var keyStore: KeyStore? = null
    private var fingerprintHex: String? = null

    /**
     * #194-13: surface keystore-load failures via [lastInitError] so callers
     * (e.g. the SecurityStatus UI) can show "TLS not available — corrupt keystore"
     * instead of silently returning {"sha256":null} from /api/fingerprint.
     */
    var lastInitError: String? = null
        private set

    init {
        try {
            loadOrCreate()
        } catch (e: Exception) {
            // Pass-5 HIGH-1: prefix so isInitErrorPermanent picks it up.
            // Exceptions reaching here escape loadOrCreate's inner catches
            // (e.g. RuntimeException from sha256Fingerprint after success, or a
            // KeyStoreException from KeyStore.getInstance("AndroidKeyStore") on a
            // device with no AndroidKeyStore provider). All terminal — the keystore
            // won't spontaneously gain a provider on the next heartbeat.
            lastInitError = "init-failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            Log.w(TAG, "keystore init failed", e)
        }
    }

    fun isReady(): Boolean = keyStore != null

    fun fingerprintSha256(): String? = fingerprintHex

    /**
     * Review-pass-3 HIGH-2: classify init failures so the caller (MonitorService's
     * heartbeat retry) knows whether trying again will help. "TLS unsupported"
     * (pre-API-23) and "gen-failed:" (RSA keygen threw, keystore corruption,
     * OOM during key write) are permanent on this install — the file isn't
     * going to spontaneously appear and the AndroidKeyStore won't recover by
     * itself. Everything else (transient "keystore still loading", IOException
     * mid-read, etc.) IS worth retrying.
     */
    fun isInitErrorPermanent(): Boolean {
        val err = lastInitError ?: return false
        return PERMANENT_PREFIXES.any { err.startsWith(it) }
    }

    fun newServerSocketFactory(): SSLServerSocketFactory? {
        val ks = keyStore ?: return null
        return try {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            // #350: AndroidKeyStore private keys are non-exportable and have no per-entry password;
            // a null password tells the KMF to use them in place (signing happens in the keystore).
            kmf.init(ks, null)
            val ctx = SSLContext.getInstance("TLS")
            val identityManagers = kmf.keyManagers.filterIsInstance<X509KeyManager>()
                .map { ServerIdentityKeyManager(it, ANDROID_ALIAS) }.toTypedArray()
            check(identityManagers.isNotEmpty()) { "TLS identity key manager unavailable" }
            ctx.init(identityManagers, null, SecureRandom())
            ctx.serverSocketFactory
        } catch (e: Exception) {
            // Pass-4 HIGH-1: prefix so isInitErrorPermanent() picks it up. #350: the keystore is now
            // the AndroidKeyStore directly; plausible causes here are NoSuchAlgorithmException on a
            // JSSE provider without TLS or an UnrecoverableKeyException / KeyPermanentlyInvalidated
            // from the keystore upcall. Treated as permanent for this install (no regen is wired into
            // the heartbeat retry); a reinstall / clear-data regenerates the key.
            lastInitError = "factory-failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            Log.w(TAG, "TLS factory build failed", e)
            null
        }
    }

    private fun loadOrCreate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Pre-API-23 has no in-platform path to self-issue a cert. Plaintext fallback.
            lastInitError = "TLS unsupported on API ${Build.VERSION.SDK_INT}"
            return
        }
        // #350: the AndroidKeyStore IS the persistent store (survives restarts), so there is no
        // PKCS12 file. Clean up any orphan from the old export path.
        if (legacyKeystoreFile.exists()) {
            try { legacyKeystoreFile.delete() } catch (_: Exception) {}
        }
        try {
            lastInitError = null
            val androidKs = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            // #350: drop orphaned earlier-version keys if present.
            for (legacy in ANDROID_ALIAS_LEGACY) {
                try { if (androidKs.containsAlias(legacy)) androidKs.deleteEntry(legacy) } catch (_: Exception) {}
            }
            val existing = androidKs.getCertificate(ANDROID_ALIAS) as? X509Certificate
            // Native parents trust the exact QR fingerprint, not a public CA or certificate dates.
            // Automatic certificate replacement would permanently invalidate every parent's pin.
            val cert: X509Certificate = if (existing != null && androidKs.isKeyEntry(ANDROID_ALIAS)) {
                existing
            } else {
                if (existing != null) {
                    Log.i(TAG, "TLS private key missing; regenerating")
                    try { androidKs.deleteEntry(ANDROID_ALIAS) } catch (_: Exception) {}
                }
                try {
                    generateKeyPair(androidKs)
                } catch (e: Exception) {
                    // #350 review (MEDIUM): generateKeyPair() writes the alias as a side effect, so a
                    // post-generation failure would leave a poisoned entry that the next boot reuses
                    // (gen-failed is classified permanent → never self-heals). Delete it so the next
                    // start regenerates cleanly.
                    try { androidKs.deleteEntry(ANDROID_ALIAS) } catch (_: Exception) {}
                    throw e
                }
            }
            keyStore = androidKs
            fingerprintHex = sha256Fingerprint(cert)
            Log.i(TAG, "TLS identity ready (fp=${fingerprintHex?.take(20)}…)")
        } catch (e: Exception) {
            lastInitError = "gen-failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            Log.w(TAG, "TLS identity init failed", e)
        }
    }

    /**
     * #350: generate an RSA-2048 keypair INSIDE AndroidKeyStore under [ANDROID_ALIAS]. The keystore
     * auto-issues a self-signed cert as part of generation. The private key never leaves the
     * keystore (hardware-backed when available) — the KMF signs in place via AndroidKeyStore, so we
     * never need (and cannot do) a PKCS12 export. Returns the issued cert (for the fingerprint).
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.M)
    private fun generateKeyPair(androidKs: KeyStore): X509Certificate {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        val start = Date()
        val end = Date(start.time + CERT_VALIDITY_MS)
        // #350: the known-good recipe for an AndroidKeyStore RSA key used as a TLS server key.
        // Conscrypt's handshake "upcalls" into the keystore for the private-key op and needs:
        //  - PURPOSE_SIGN  : ECDHE_RSA suites (server signs the key exchange) — the forward-secret path
        //  - PURPOSE_DECRYPT: RSA key-transport suites + the raw-RSA upcall fallback
        //  - DIGEST_NONE (+ specific digests): Conscrypt pre-hashes and asks for a raw "NONEwithRSA" sign
        //  - both PKCS1 paddings; randomizedEncryptionRequired(false) so deterministic RSA decrypt works
        val spec = KeyGenParameterSpec.Builder(
            ANDROID_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(2048)
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
                KeyProperties.DIGEST_SHA1
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS
            )
            // ENCRYPTION_PADDING_NONE is essential: Conscrypt's signature upcall does a RAW RSA op
            // via Cipher("RSA/ECB/NoPadding"); without NONE the keystore rejects it ("Incompatible
            // padding mode"). PKCS1 covers RSA key-transport decrypt.
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE,
                KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1
            )
            .setRandomizedEncryptionRequired(false)
            .setCertificateSubject(X500Principal("CN=childmonitor-local, O=ChildMonitor"))
            .setCertificateSerialNumber(BigInteger.valueOf(start.time))
            .setCertificateNotBefore(start)
            .setCertificateNotAfter(end)
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
        return androidKs.getCertificate(ANDROID_ALIAS) as X509Certificate
    }

    private fun sha256Fingerprint(cert: X509Certificate): String = fingerprintOf(cert) ?: ""

    companion object {
        private const val TAG = "TlsIdentity"
        // #350: orphan PKCS12 file from the old (broken) export path — deleted on init if present.
        private const val LEGACY_KEYSTORE_FILE = "local_tls.p12"
        // #350: v3 — earlier aliases can't satisfy Conscrypt's TLS RSA upcall (v1 was sign-only;
        // v2 lacked ENCRYPTION_PADDING_NONE for the raw-RSA signature path). v3 uses the full
        // known-good TLS recipe. Stale aliases are deleted on init.
        private const val ANDROID_ALIAS = "childmonitor-tls-v3"
        private val ANDROID_ALIAS_LEGACY = listOf("childmonitor-tls-v1", "childmonitor-tls-v2")
        private const val CERT_VALIDITY_MS = 5L * 365L * 24L * 60L * 60L * 1000L  // 5y
        // Pass-5: single source of truth for permanent prefixes — every catch
        // block in this file that writes lastInitError MUST emit one of these
        // when the error is non-recoverable so the MonitorService heartbeat
        // doesn't burn ticks retrying. Kept on the companion so unit tests can
        // assert against it directly.
        internal val PERMANENT_PREFIXES = listOf(
            "TLS unsupported",
            "gen-failed",
            "factory-failed",
            "init-failed"
        )

        @Volatile private var instance: TlsIdentity? = null
        private val instanceLock = Any()

        /**
         * Process-wide singleton. The first call performs keystore load (and on
         * first run keypair generation, which can take several seconds for
         * RSA-2048 on older devices) — schedule that first call from a
         * background dispatcher (see [prime]) before any UI thread asks for the
         * fingerprint. Subsequent calls are constant-time.
         */
        fun getInstance(context: Context): TlsIdentity {
            instance?.let { return it }
            return synchronized(instanceLock) {
                instance ?: TlsIdentity(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Warm the singleton on a background thread. Safe to call from
         * `Service.onCreate`; subsequent main-thread `getInstance` calls will
         * find the cached value without paying the keygen cost. Returns
         * immediately if already primed.
         */
        fun prime(context: Context) {
            if (instance != null) return
            val appCtx = context.applicationContext
            Thread({
                try { getInstance(appCtx) } catch (e: Throwable) { android.util.Log.w(TAG, "prime failed", e) }
            }, "TlsIdentity-prime").apply { isDaemon = true }.start()
        }

        /** Test-only seam. */
        internal fun resetForTest() { synchronized(instanceLock) { instance = null } }

        /**
         * SHA-256 fingerprint in colon-separated uppercase hex, suitable for
         * comparison against a value the parent received at pairing time.
         */
        fun fingerprintOf(cert: X509Certificate?): String? {
            if (cert == null) return null
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(cert.encoded)
                buildString(digest.size * 3) {
                    for (b in digest) {
                        if (isNotEmpty()) append(':')
                        append("%02X".format(b.toInt() and 0xff))
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
