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
package de.rochefort.childmonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.backup.BackupCodec
import de.rochefort.childmonitor.backup.BackupManager
import de.rochefort.childmonitor.backup.BackupSerializer
import java.io.ByteArrayOutputStream

/**
 * #310: standalone, passphrase-protected backup/restore of the parent's settings & pairings.
 *
 * "Create" writes an encrypted .cmbak via SAF (ACTION_CREATE_DOCUMENT); "Restore" reads one
 * (ACTION_OPEN_DOCUMENT) behind a confirmation. The crypto (600k-iteration PBKDF2) is slow, so both
 * run off the UI thread. The passphrase is handled as a CharArray (read from the field via getChars,
 * never a String) and zeroed in a finally; the decrypted/restored bytes are scrubbed by BackupManager.
 *
 * Plain android.app.Activity (no androidx) → classic startActivityForResult/onActivityResult. The
 * pending passphrase + op survive the SAF round-trip in fields (configChanges keeps the Activity).
 */
class BackupActivity : Activity() {

    private companion object {
        const val REQ_CREATE = 1
        const val REQ_OPEN = 2
        const val MIN_PASSPHRASE = 8
    }

    private lateinit var passwordEdit: EditText
    private lateinit var statusText: TextView
    @Volatile private var pendingPassphrase: CharArray? = null
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The passphrase + status appear here; keep them out of screenshots / recents thumbnails.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        title = getString(R.string.backupTitle)
        val p = ThemeManager.getCurrentPalette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(p.gradientStart, p.gradientMid, p.gradientEnd),
            )
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.backupTitle)
            setTextColor(p.textPrimary); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.backupExplanation)
            setTextColor(p.textSecondary); textSize = 14f
            setPadding(0, 0, 0, dp(14))
        })
        passwordEdit = EditText(this).apply {
            hint = getString(R.string.backupPassphraseHint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // Keep the backup passphrase out of keyboard personalised-learning/suggestions.
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setTextColor(p.textPrimary); setHintTextColor(p.textHint)
        }
        root.addView(passwordEdit)
        root.addView(Button(this).apply {
            text = getString(R.string.backupCreate); isAllCaps = false
            background = pill(p.buttonPrimary, p.surfaceBorder, dp(12).toFloat(), dp(1))
            setTextColor(if (ThemeManager.isLightColorForUi(p.buttonPrimary)) ThemeManager.onLightButtonText else p.textPrimary)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onCreateBackupClicked() }
            layoutParams = matchWrap().apply { topMargin = dp(14) }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.backupRestore); isAllCaps = false
            background = pill(p.buttonSecondary, p.buttonSecondaryBorder, dp(12).toFloat(), dp(1))
            setTextColor(if (ThemeManager.isLightColorForUi(p.buttonSecondary)) ThemeManager.onLightButtonText else p.textPrimary)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onRestoreClicked() }
            layoutParams = matchWrap().apply { topMargin = dp(10) }
        })
        statusText = TextView(this).apply {
            setTextColor(p.textSecondary); textSize = 13f; setPadding(0, dp(14), 0, 0)
        }
        root.addView(statusText)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun pill(fill: Int, border: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius; setColor(fill); setStroke(stroke, border)
    }

    /** Read the passphrase into a fresh CharArray without materialising a String. */
    private fun readPassphrase(): CharArray {
        val len = passwordEdit.text.length
        val pw = CharArray(len)
        passwordEdit.text.getChars(0, len, pw, 0)
        return pw
    }

    private fun onCreateBackupClicked() {
        if (busy) return
        val pw = readPassphrase()
        if (pw.size < MIN_PASSPHRASE) {
            pw.fill('\u0000')
            toast(getString(R.string.backupPassphraseTooShort, MIN_PASSPHRASE)); return
        }
        pendingPassphrase = pw
        val name = "child-monitor-backup-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".cmbak"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(intent, REQ_CREATE)
        } catch (e: Exception) {
            clearPending(); toast(getString(R.string.backupNoFilePicker))
        }
    }

    private fun onRestoreClicked() {
        if (busy) return
        val pw = readPassphrase()
        if (pw.isEmpty()) { toast(getString(R.string.backupPassphraseNeeded)); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.backupRestore)
            .setMessage(R.string.backupRestoreWarning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.backupRestore) { _, _ ->
                pendingPassphrase = pw.copyOf() // copy: dismiss zeros the original
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                try {
                    startActivityForResult(intent, REQ_OPEN)
                } catch (e: Exception) {
                    clearPending(); toast(getString(R.string.backupNoFilePicker))
                }
            }
            .setOnDismissListener { pw.fill('\u0000') } // zero original on every close path
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { clearPending(); return }
        when (requestCode) {
            REQ_CREATE -> runBackup(uri)
            REQ_OPEN -> runRestore(uri)
        }
    }

    private fun runBackup(uri: android.net.Uri) {
        // pendingPassphrase can be null if our process was killed behind the SAF picker (low-RAM) —
        // configChanges survives rotation but not process death. Don't silently no-op (the user picked
        // a location and would get a 0-byte file with no feedback); tell them to retry.
        val pw = pendingPassphrase ?: run {
            toast(getString(R.string.backupInterrupted))
            runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
            return
        }
        pendingPassphrase = null
        busy = true
        setStatus(getString(R.string.backupWorking))
        Thread {
            val result = try {
                val blob = BackupManager.createEncrypted(applicationContext, pw, System.currentTimeMillis())
                contentResolver.openOutputStream(uri)?.use { it.write(blob) }
                    ?: throw java.io.IOException("could not open file for writing")
                getString(R.string.backupCreated)
            } catch (e: Exception) {
                android.util.Log.w("BackupActivity", "Backup failed: ${e.javaClass.simpleName}")
                // Don't leave a 0-byte/partial document behind to fail a later restore.
                runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
                getString(R.string.backupFailed)
            } finally {
                pw.fill('\u0000')
            }
            finishOp(result)
        }.apply { name = "BackupActivity-create"; isDaemon = true }.start()
    }

    private fun runRestore(uri: android.net.Uri) {
        // See runBackup: null after process death behind the picker — surface it, don't no-op.
        val pw = pendingPassphrase ?: run {
            toast(getString(R.string.backupInterrupted))
            return
        }
        pendingPassphrase = null
        busy = true
        setStatus(getString(R.string.backupWorking))
        Thread {
            val result = try {
                val bytes = readBounded(uri)
                val n = BackupManager.restore(applicationContext, bytes, pw)
                resources.getQuantityString(R.plurals.backupRestored, n, n)
            } catch (e: BackupCodec.AuthFailed) {
                getString(R.string.backupWrongPassphrase)
            } catch (e: BackupCodec.BadFormat) {
                getString(R.string.backupNotABackup)
            } catch (e: BackupSerializer.Invalid) {
                getString(R.string.backupNotABackup)
            } catch (e: Exception) {
                android.util.Log.w("BackupActivity", "Restore failed: ${e.javaClass.simpleName}")
                getString(R.string.backupFailed)
            } finally {
                pw.fill('\u0000')
            }
            finishOp(result)
        }.apply { name = "BackupActivity-restore"; isDaemon = true }.start()
    }

    /** Read the chosen file, refusing anything over the cap before buffering it all. */
    private fun readBounded(uri: android.net.Uri): ByteArray {
        val cap = BackupManager.MAX_BACKUP_BYTES
        return contentResolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val read = ins.read(chunk)
                if (read < 0) break
                total += read
                if (total > cap) throw java.io.IOException("backup file too large")
                buf.write(chunk, 0, read)
            }
            buf.toByteArray()
        } ?: throw java.io.IOException("could not open file")
    }

    private fun finishOp(message: String) {
        runOnUiThread {
            busy = false
            if (isFinishing || isDestroyed) return@runOnUiThread
            statusText.text = message
            passwordEdit.text?.clear() // drop the typed passphrase from the field after the op
            toast(message)
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread { if (!isFinishing && !isDestroyed) statusText.text = message }
    }

    private fun clearPending() {
        pendingPassphrase?.fill('\u0000')
        pendingPassphrase = null
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        clearPending()
        super.onDestroy()
    }
}
