/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.rochefort.childmonitor.backup

/** Pure allow/deny policy shared by backup export and restore validation (#465). */
internal object BackupKeyPolicy {
    private val exactExcluded = setOf(
        "autoStartChild", "autoStartParent",
        "sessionActive", "sessionAddress", "sessionName", "sessionPort",
        "appLockPinHash", "appLockPinSalt", "appLockPinFails", "appLockPinLastFailMs",
        "appLockAutoDisabledNotice",
    )

    // Include both historical plaintext names and current ciphertext. Ciphertext is bound to the
    // source phone's non-exportable Keystore key and is neither portable nor appropriate in an
    // export. Restore applies this same list so an older/crafted backup cannot reintroduce secrets.
    private val parentCredentialPrefixes = listOf(
        "token_v1::",
        "refresh_v1::",
        "token_enc_v2::",
        "refresh_enc_v2::",
        "credential_error_v1::",
    )

    @Suppress("UNUSED_PARAMETER")
    fun isExcluded(file: String, key: String): Boolean =
        key in exactExcluded ||
            parentCredentialPrefixes.any(key::startsWith)
}
