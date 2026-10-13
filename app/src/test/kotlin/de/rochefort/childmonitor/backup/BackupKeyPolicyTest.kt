package de.rochefort.childmonitor.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupKeyPolicyTest {
    @Test
    fun parentCredentialsAreExcludedInLegacyAndEncryptedFormats() {
        for (key in listOf(
            "token_v1::kid",
            "refresh_v1::kid",
            "token_enc_v2::kid",
            "refresh_enc_v2::kid",
            "credential_error_v1::bearer::kid",
        )) {
            assertTrue(key, BackupKeyPolicy.isExcluded("ParentSession", key))
        }
    }

    @Test
    fun tlsRecoveryMetadataRemainsExportable() {
        assertFalse(BackupKeyPolicy.isExcluded("ParentSession", "fp_v1::kid"))
        assertFalse(BackupKeyPolicy.isExcluded("ParentSession", "https_port_v1::kid"))
        assertFalse(BackupKeyPolicy.isExcluded("ParentSession", "ever_pinned_v1::kid"))
    }

    @Test
    fun craftedBackupCannotHideASecretPrefixInAnotherAllowedKey() {
        assertTrue(BackupKeyPolicy.isExcluded("ListenPreferences", "token_enc_v2::kid"))
        assertTrue(BackupKeyPolicy.isExcluded("ListenPreferences", "autoStartChild"))
    }
}
