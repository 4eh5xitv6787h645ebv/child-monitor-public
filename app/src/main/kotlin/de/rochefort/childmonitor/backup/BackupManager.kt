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
package de.rochefort.childmonitor.backup

import android.content.Context

/**
 * #310: ties the pure [BackupSerializer] + [BackupCodec] to Android SharedPreferences — gathers the
 * parent-side settings + pairings into an encrypted blob and restores them.
 *
 * Security posture (restore handles non-secret settings + TLS pins):
 *  - Only a fixed [ALLOWED_FILES] allow-list is ever read OR written, so a correctly-decrypted but
 *    malicious backup cannot inject data into arbitrary preference files.
 *  - The decrypted plaintext is never logged and is zeroed after use; the passphrase stays a
 *    CharArray (the caller owns clearing it).
 *  - Structural validation (caps, type tags, version) happens in [BackupSerializer.parse]; GCM proves
 *    integrity. Per-value semantic validation is defended at the SINKS: restored child addresses/ports
 *    are validated in PairedChild.fromJson (unsafe ip blanked, out-of-range port defaulted — #423),
 *    TLS pin can only ever make a handshake stricter (cert compare fails closed). Bearer/refresh
 *    credentials are excluded on both export and restore; current ciphertext is device-bound and
 *    historical plaintext must never be reintroduced (#465).
 */
object BackupManager {

    /** Parent-side "settings & pairings". NOT child-role files, NOT the baby-tracker sub-app. */
    val ALLOWED_FILES = listOf(
        "ListenPreferences",          // settings + pairedChildren_v1 + defaultKid_v1 + ipHistory
        "ParentSession",              // TLS pins/ports only; credentials are filtered below
        "ThemePreferences",
        "UpdateChecker",
        "AudioCalibrationPreferences",
    )

    /** Transient/runtime keys that must NOT be backed up or restored (excluded on BOTH paths, so a
     *  crafted backup can't smuggle them in either): the boot auto-start toggles + guard counter
     *  (restoring auto-start onto another phone could make it unexpectedly launch on boot — the
     *  boot-loop hazard), and the watchdog's current-session state (stale → could re-arm a restart).
     *  The user re-opts into auto-start on the new device. */
    /** Reject an import larger than this before decrypting — a settings/pairings backup is KBs; this
     *  bounds the read+decrypt+JSON-parse memory so a huge file can't OOM the restore (Codex/review). */
    const val MAX_BACKUP_BYTES = 8 * 1024 * 1024

    // NOTE: createEncrypted's `serialize(...).toString()` and restore's `String(plain)` materialise an
    // immutable String of the secret-bearing JSON that can't be zeroed and lingers until GC — inherent
    // to using org.json. The fill(0) below scrubs the byte buffers we DO control; the String residue is
    // an accepted JVM caveat for this design (a streaming serializer would be overkill here).

    /** Gather the allow-listed prefs, serialise, and encrypt under [passphrase]. */
    fun createEncrypted(context: Context, passphrase: CharArray, nowMs: Long): ByteArray {
        val prefs = LinkedHashMap<String, Map<String, Any?>>()
        for (file in ALLOWED_FILES) {
            val all = context.getSharedPreferences(file, Context.MODE_PRIVATE).all
                .filterKeys { !BackupKeyPolicy.isExcluded(file, it) }
            if (all.isNotEmpty()) prefs[file] = all
        }
        val plain = BackupSerializer.serialize(prefs, nowMs).toByteArray(Charsets.UTF_8)
        return try {
            BackupCodec.encrypt(plain, passphrase)
        } finally {
            plain.fill(0) // scrub the serialized tokens/pins
        }
    }

    /**
     * Decrypt + validate + apply [blob]. REPLACES each restored allow-listed pref file's contents.
     * @return number of (key) entries written.
     * @throws BackupCodec.AuthFailed wrong passphrase or corrupt/tampered blob
     * @throws BackupCodec.BadFormat not a backup / unsupported version / out-of-range iterations
     * @throws BackupSerializer.Invalid decrypted payload isn't a valid backup (bad structure / over a cap)
     * @throws java.io.IOException a restored pref file failed to persist
     */
    fun restore(context: Context, blob: ByteArray, passphrase: CharArray): Int {
        if (blob.size > MAX_BACKUP_BYTES) throw BackupCodec.BadFormat("backup file too large")
        val plain = BackupCodec.decrypt(blob, passphrase)
        try {
            val backup = BackupSerializer.parse(String(plain, Charsets.UTF_8))
            var count = 0
            for ((file, entries) in backup.prefs) {
                if (file !in ALLOWED_FILES) continue // ignore anything outside the allow-list
                // Overlay (put-over), NOT clear()+put. ListenPreferences is a shared multi-feature
                // file (AppLock PIN, detection prefs, watchdog, …); clearing it would wipe current
                // settings absent from the backup (e.g. restoring an older backup would delete the
                // AppLock PIN). Single-value keys like pairedChildren_v1/ipHistory are still replaced
                // wholesale, so the restored roster/history is exact; only co-located unrelated keys
                // survive. (Cost: an orphan token_v1::<id> for a kid not in the restored roster is
                // inert — no roster entry → unused.)
                val editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
                for ((key, value) in entries) {
                    if (BackupKeyPolicy.isExcluded(file, key)) continue
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                    count++
                }
                // Synchronous + durable per file (rare, user-initiated; runs off the UI thread). Surface
                // a persistence failure instead of falsely reporting success.
                if (!editor.commit()) throw java.io.IOException("failed to persist restored prefs for $file")
            }
            return count
        } finally {
            plain.fill(0) // scrub decrypted tokens/pins
        }
    }
}
