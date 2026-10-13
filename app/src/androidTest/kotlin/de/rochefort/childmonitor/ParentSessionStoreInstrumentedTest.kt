package de.rochefort.childmonitor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.rochefort.childmonitor.security.ParentCredentialCrypto
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParentSessionStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        deleteWrappingKey()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        deleteWrappingKey()
    }

    @Test
    fun newBearerAndRefreshValuesAreEncryptedAtRest() {
        assertTrue(ParentSessionStore.put(context, DEVICE_ID, BEARER))
        assertTrue(ParentSessionStore.putRefreshToken(context, DEVICE_ID, REFRESH, 123L, 456L))

        assertEquals(BEARER, ParentSessionStore.get(context, DEVICE_ID))
        assertEquals(REFRESH, ParentSessionStore.getRefreshToken(context, DEVICE_ID))
        assertNoPlaintextSecrets()
        assertTrue(prefs.all.keys.any { it.startsWith("token_enc_v2::") })
        assertTrue(prefs.all.keys.any { it.startsWith("refresh_enc_v2::") })
    }

    @Test
    fun independentParentIdentityAndSecretsAreSavedTogether() {
        assertEquals(DEVICE_ID, ParentSessionStore.refreshDeviceId(context, DEVICE_ID))
        assertFalse(ParentSessionStore.hasRefreshDeviceId(context, DEVICE_ID))
        assertTrue(ParentSessionStore.putRefreshToken(
            context, DEVICE_ID, REFRESH, 123L, Long.MAX_VALUE,
            refreshDeviceId = "parent-a", bearerToken = BEARER,
        ))
        assertEquals("parent-a", ParentSessionStore.refreshDeviceId(context, DEVICE_ID))
        assertEquals(BEARER, ParentSessionStore.get(context, DEVICE_ID))
        assertEquals(REFRESH, ParentSessionStore.getRefreshToken(context, DEVICE_ID))
        assertNoPlaintextSecrets()
        ParentSessionStore.forget(context, DEVICE_ID)
        assertEquals("parent-a", ParentSessionStore.refreshDeviceId(context, DEVICE_ID))
        assertEquals(REFRESH, ParentSessionStore.getRefreshToken(context, DEVICE_ID))
        ParentSessionStore.forgetAll(context, DEVICE_ID)
        assertEquals(DEVICE_ID, ParentSessionStore.refreshDeviceId(context, DEVICE_ID))
        assertFalse(ParentSessionStore.hasRefreshDeviceId(context, DEVICE_ID))
    }

    @Test
    fun lateRevocationAndRenewalCannotEraseAConcurrentRepair() {
        assertTrue(ParentSessionStore.putRefreshToken(
            context, DEVICE_ID, REFRESH, 123L, Long.MAX_VALUE, bearerToken = BEARER,
        ))
        assertTrue(ParentSessionStore.putRefreshToken(
            context, DEVICE_ID, "new-refresh-secret", 456L, Long.MAX_VALUE,
            refreshDeviceId = "parent-new", bearerToken = REPAIRED_BEARER,
        ))
        assertFalse(ParentSessionStore.forgetRejectedRefresh(context, DEVICE_ID, REFRESH, BEARER))
        assertFalse(ParentSessionStore.putRenewedBearer(context, DEVICE_ID, "stale-renewal", REFRESH, BEARER))
        assertEquals(REPAIRED_BEARER, ParentSessionStore.get(context, DEVICE_ID))
        assertEquals("new-refresh-secret", ParentSessionStore.getRefreshToken(context, DEVICE_ID))
        assertEquals("parent-new", ParentSessionStore.refreshDeviceId(context, DEVICE_ID))
        assertNull(ParentSessionStore.credentialFailureFor(context, DEVICE_ID))
    }

    @Test
    fun lateUnauthorizedResponseUsesTheAlreadyRenewedBearerWithoutNetworkAccess() {
        assertTrue(ParentSessionStore.put(context, DEVICE_ID, REPAIRED_BEARER))
        assertEquals(
            de.rochefort.childmonitor.parent.SessionRenewer.RenewResult.RENEWED,
            de.rochefort.childmonitor.parent.SessionRenewer.recoverRejectedBearer(
                context, DEVICE_ID, "127.0.0.1", BEARER,
            ),
        )
        assertEquals(REPAIRED_BEARER, ParentSessionStore.get(context, DEVICE_ID))
    }

    @Test
    fun legacyPlaintextMigratesInOnePreferenceTransaction() {
        prefs.edit()
            .putString("token_v1::$DEVICE_ID", BEARER)
            .putString("refresh_v1::$DEVICE_ID", REFRESH)
            .putLong("refresh_at_v1::$DEVICE_ID", 123L)
            .putLong("refresh_ttl_v1::$DEVICE_ID", 456L)
            .commit()

        assertEquals(BEARER, ParentSessionStore.get(context, DEVICE_ID))
        assertEquals(REFRESH, ParentSessionStore.getRefreshToken(context, DEVICE_ID))

        assertFalse(prefs.contains("token_v1::$DEVICE_ID"))
        assertFalse(prefs.contains("refresh_v1::$DEVICE_ID"))
        assertNoPlaintextSecrets()
        assertEquals(123L, ParentSessionStore.refreshIssuedAt(context, DEVICE_ID))
        assertEquals(456L, ParentSessionStore.refreshTtlMs(context, DEVICE_ID))
    }

    @Test
    fun authenticatedCiphertextCorruptionFailsClosedAndRequestsRepair() {
        assertTrue(ParentSessionStore.put(context, DEVICE_ID, BEARER))
        val encryptedKey = prefs.all.keys.single { it.startsWith("token_enc_v2::") }
        val envelope = prefs.getString(encryptedKey, null)!!
        val parts = envelope.split('.').toMutableList()
        assertEquals(4, parts.size)
        val ciphertext = parts.last()
        val replacement = if (ciphertext.first() == 'A') 'B' else 'A'
        parts[parts.lastIndex] = replacement + ciphertext.drop(1)
        val corruptedEnvelope = parts.joinToString(".")
        assertNotEquals("fixture must alter authenticated ciphertext", envelope, corruptedEnvelope)
        prefs.edit().putString(encryptedKey, corruptedEnvelope).commit()

        assertNull(ParentSessionStore.get(context, DEVICE_ID))
        assertEquals(
            ParentSessionStore.CredentialFailure.REPAIR_REQUIRED,
            ParentSessionStore.credentialFailureFor(context, DEVICE_ID),
        )
        assertFalse(prefs.contains("token_v1::$DEVICE_ID"))
    }

    @Test
    fun keystoreInvalidationFailsClosedUntilExplicitRepairStoresANewCredential() {
        assertTrue(ParentSessionStore.put(context, DEVICE_ID, BEARER))
        deleteWrappingKey()

        assertNull(ParentSessionStore.get(context, DEVICE_ID))
        assertEquals(
            ParentSessionStore.CredentialFailure.REPAIR_REQUIRED,
            ParentSessionStore.credentialFailureFor(context, DEVICE_ID),
        )

        assertTrue(ParentSessionStore.put(context, DEVICE_ID, REPAIRED_BEARER))
        assertEquals(REPAIRED_BEARER, ParentSessionStore.get(context, DEVICE_ID))
        assertNull(ParentSessionStore.credentialFailureFor(context, DEVICE_ID))
        assertNoPlaintextSecrets()
    }

    @Test
    fun revokeAndRemoveDeleteCiphertextAndNonSecretFailureState() {
        assertTrue(ParentSessionStore.put(context, DEVICE_ID, BEARER))
        assertTrue(ParentSessionStore.putRefreshToken(context, DEVICE_ID, REFRESH, 123L, 456L))

        ParentSessionStore.forget(context, DEVICE_ID)
        assertNull(ParentSessionStore.get(context, DEVICE_ID))
        assertEquals(REFRESH, ParentSessionStore.getRefreshToken(context, DEVICE_ID))

        ParentSessionStore.forgetRefresh(context, DEVICE_ID)
        assertNull(ParentSessionStore.getRefreshToken(context, DEVICE_ID))
        assertEquals(
            ParentSessionStore.CredentialFailure.REPAIR_REQUIRED,
            ParentSessionStore.credentialFailureFor(context, DEVICE_ID),
        )

        ParentSessionStore.forgetAll(context, DEVICE_ID)
        assertNull(ParentSessionStore.credentialFailureFor(context, DEVICE_ID))
        assertFalse(prefs.all.keys.any { it.contains(DEVICE_ID) })
    }

    private fun assertNoPlaintextSecrets() {
        val persisted = prefs.all.values.joinToString("|")
        assertFalse(persisted.contains(BEARER))
        assertFalse(persisted.contains(REFRESH))
        assertFalse(persisted.contains(REPAIRED_BEARER))
    }

    private fun deleteWrappingKey() {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(ParentCredentialCrypto.KEY_ALIAS)) {
                deleteEntry(ParentCredentialCrypto.KEY_ALIAS)
            }
        }
    }

    companion object {
        private const val PREFS = "ParentSession"
        private const val DEVICE_ID = "instrumented-child-465"
        private const val BEARER = "bearer-secret-that-must-never-be-plaintext"
        private const val REFRESH = "refresh-secret-that-must-never-be-plaintext"
        private const val REPAIRED_BEARER = "replacement-secret-after-explicit-repair"
    }
}
