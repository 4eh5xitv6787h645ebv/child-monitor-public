/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.rochefort.childmonitor.security

import android.content.Context
import android.annotation.TargetApi
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

internal enum class ParentCredentialKind(val aadLabel: String) {
    BEARER("bearer"),
    REFRESH("refresh"),
    MQTT_PASSWORD("mqtt_password"),
}

/**
 * API-21-compatible envelope encryption for app credentials (#465/#305).
 *
 * AndroidKeyStore did not support symmetric AES keys until API 23. To preserve the app's API 21
 * floor, a non-exportable RSA key wraps a fresh random AES key for each value; AES-GCM protects the
 * credential itself and authenticates its kind + child id as AAD. This protects both parent pairing
 * secrets and the optional child-side MQTT password. No key requires user unlock, because unattended
 * monitoring must recover after boot. A missing/invalidated key fails closed at the owning store.
 */
internal object ParentCredentialCrypto {
    internal const val KEY_ALIAS = "cm_parent_credential_wrap_v1"

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val ENVELOPE_VERSION = "v1"
    private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AES_KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private val lock = Any()
    private val random = SecureRandom()

    fun encrypt(
        context: Context,
        childDeviceId: String,
        kind: ParentCredentialKind,
        plaintext: String,
    ): String = synchronized(lock) {
        require(childDeviceId.isNotBlank())
        require(plaintext.isNotBlank())

        val dataKey = KeyGenerator.getInstance("AES").apply { init(AES_KEY_BITS, random) }.generateKey()
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val aes = Cipher.getInstance(AES_TRANSFORMATION)
        aes.init(Cipher.ENCRYPT_MODE, dataKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        aes.updateAAD(aad(childDeviceId, kind))
        val ciphertext = aes.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val rsa = Cipher.getInstance(RSA_TRANSFORMATION)
        rsa.init(Cipher.ENCRYPT_MODE, publicKeyOrCreate(context.applicationContext))
        val wrappedKey = rsa.doFinal(dataKey.encoded)

        listOf(
            ENVELOPE_VERSION,
            encode(wrappedKey),
            encode(iv),
            encode(ciphertext),
        ).joinToString(".")
    }

    fun decrypt(
        childDeviceId: String,
        kind: ParentCredentialKind,
        envelope: String,
    ): String = synchronized(lock) {
        require(childDeviceId.isNotBlank())
        val parts = envelope.split('.')
        require(parts.size == 4 && parts[0] == ENVELOPE_VERSION)
        val wrappedKey = decode(parts[1])
        val iv = decode(parts[2])
        val ciphertext = decode(parts[3])
        require(iv.size == IV_BYTES && wrappedKey.isNotEmpty() && ciphertext.isNotEmpty())

        val rsa = Cipher.getInstance(RSA_TRANSFORMATION)
        rsa.init(Cipher.DECRYPT_MODE, privateKey())
        val rawKey = rsa.doFinal(wrappedKey)
        require(rawKey.size == AES_KEY_BITS / 8)

        val aes = Cipher.getInstance(AES_TRANSFORMATION)
        aes.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(rawKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        aes.updateAAD(aad(childDeviceId, kind))
        val plaintext = aes.doFinal(ciphertext).toString(Charsets.UTF_8)
        require(plaintext.isNotBlank())
        plaintext
    }

    private fun publicKeyOrCreate(context: Context): PublicKey {
        val keyStore = loadKeyStore()
        keyStore.getCertificate(KEY_ALIAS)?.publicKey?.let { return it }

        val generator = KeyPairGenerator.getInstance("RSA", ANDROID_KEY_STORE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            initializeModern(generator)
        } else {
            initializeLegacy(context, generator)
        }
        generator.generateKeyPair()
        return loadKeyStore().getCertificate(KEY_ALIAS)?.publicKey
            ?: throw java.security.KeyStoreException("credential wrapping key unavailable")
    }

    @Suppress("DEPRECATION")
    private fun initializeLegacy(context: Context, generator: KeyPairGenerator) {
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
        val spec = android.security.KeyPairGeneratorSpec.Builder(context)
            .setAlias(KEY_ALIAS)
            // Compile-time String constant ("RSA"); safe on API 21 even though the symbolic
            // owner was added in API 23, and it satisfies lint's KeyAlgorithm contract.
            .setKeyType(KeyProperties.KEY_ALGORITHM_RSA)
            .setKeySize(2048)
            .setSubject(X500Principal("CN=Child Monitor Parent Credential Key"))
            .setSerialNumber(BigInteger.ONE)
            .setStartDate(start.time)
            .setEndDate(end.time)
            .build()
        generator.initialize(spec)
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun initializeModern(generator: KeyPairGenerator) {
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setUserAuthenticationRequired(false)
                .build()
        )
    }

    private fun privateKey(): PrivateKey {
        val key = loadKeyStore().getKey(KEY_ALIAS, null)
        return key as? PrivateKey
            ?: throw java.security.KeyStoreException("credential wrapping key unavailable")
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun aad(childDeviceId: String, kind: ParentCredentialKind): ByteArray =
        "child-monitor|parent-credential|$ENVELOPE_VERSION|${kind.aadLabel}|$childDeviceId"
            .toByteArray(Charsets.UTF_8)

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, BASE64_FLAGS)
    private fun decode(value: String): ByteArray = Base64.decode(value, BASE64_FLAGS)
}
