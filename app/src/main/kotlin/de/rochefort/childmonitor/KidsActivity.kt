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
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Parent-side "Kids" roster. Lists every paired child device by its PARENT-assigned name
 * (e.g. "kid1") as a PERMANENT link: tap a kid to reconnect no matter how its IP changed,
 * with no re-pairing — the stable deviceId selects the stored TLS pin + bearer, and the
 * durable refresh credential (#348) self-heals an expired bearer over the pinned channel.
 *
 * Naming a child here ([rename]) marks it permanent (it is exempt from the recency cap and
 * reconnects never overwrite the name — see [PairedChildRoster]). New children are added by
 * scanning their QR code ([ParentScanActivity]); there is no other pairing path.
 */
class KidsActivity : Activity() {

    private lateinit var kidsContainer: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kids)
        ThemeManager.applyTheme(this)

        kidsContainer = findViewById(R.id.kidsContainer)
        emptyText = findViewById(R.id.kidsEmptyText)

        findViewById<Button>(R.id.kidsAddButton).setOnClickListener {
            // The only way to add a child is to scan its QR code; pairing persists the
            // deviceId + TLS pin + bearer, after which it shows up here to be named.
            startActivity(Intent(this, ParentScanActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.applyTheme(this)
        render()
    }

    private fun render() {
        val kids = PairedChildrenStore.list(this)
        val defaultId = PairedChildrenStore.getDefaultKidDeviceId(this)
        kidsContainer.removeAllViews()
        if (kids.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            emptyText.text = getString(R.string.kidsEmpty)
            return
        }
        emptyText.visibility = View.GONE
        kids.forEach { kidsContainer.addView(buildCard(it, it.deviceId == defaultId)) }
        // Re-theme so the freshly-added cards/buttons pick up tagged backgrounds + text contrast.
        ThemeManager.applyTheme(this)
    }

    private fun buildCard(kid: PairedChild, isDefault: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundResource(R.drawable.bg_surface_soft)
            tag = "theme_soft"
        }

        // Title row: the kid name + a star that toggles "default kid" (Quick-Reconnect / boot target).
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = kid.displayName
            textSize = 17f
            setTextColor(ContextCompat.getColor(this@KidsActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val star = TextView(this).apply {
            text = if (isDefault) "★" else "☆"
            textSize = 22f
            setPadding(16, 0, 4, 0)
            setTextColor(ContextCompat.getColor(this@KidsActivity,
                if (isDefault) R.color.text_primary else R.color.text_secondary))
            contentDescription = getString(if (isDefault) R.string.kidDefaultSet else R.string.kidSetDefault)
            setOnClickListener { toggleDefault(kid, isDefault) }
        }
        titleRow.addView(title)
        titleRow.addView(star)

        val details = TextView(this).apply {
            text = describe(kid, isDefault)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@KidsActivity, R.color.text_secondary))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 }
            layoutParams = lp
        }
        buttonRow.addView(rowButton(R.string.kidConnect, R.drawable.bg_button_primary, "theme_btn_primary") {
            connect(kid)
        })
        buttonRow.addView(rowButton(R.string.kidRename, R.drawable.bg_button_secondary, "theme_btn_secondary") {
            rename(kid)
        })
        buttonRow.addView(rowButton(R.string.kidRemove, R.drawable.bg_button_secondary, "theme_btn_secondary") {
            confirmRemove(kid)
        })

        card.addView(titleRow)
        card.addView(details)
        card.addView(buttonRow)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 }
        return card
    }

    private fun rowButton(textRes: Int, bgRes: Int, themeTag: String, onClick: () -> Unit): Button =
        Button(this).apply {
            setText(textRes)
            isAllCaps = false
            textSize = 13f
            setBackgroundResource(bgRes)
            tag = themeTag
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = 8 }
            setOnClickListener { onClick() }
        }

    /** The secondary details line: permanence/default, the child-advertised name (when it differs), last endpoint, added date. */
    private fun describe(kid: PairedChild, isDefault: Boolean): String = buildString {
        append(if (kid.isPermanent) getString(R.string.kidPermanentBadge) else getString(R.string.kidNotSaved))
        if (isDefault) {
            append("\n")
            append(getString(R.string.kidDefaultBadge))
        }
        if (kid.userName != null && kid.name.isNotBlank() && kid.name != kid.userName) {
            append("\n")
            append(getString(R.string.kidAdvertisedName, kid.name))
        }
        if (kid.lastKnownIp.isNotBlank()) {
            append("\n")
            append(getString(R.string.kidLastSeenAt, "${kid.lastKnownIp}:${kid.lastKnownPort}"))
        }
        if (kid.createdAtMs > 0L) {
            append("\n")
            append(getString(R.string.kidAddedAt, DateFormat.format("yyyy-MM-dd", kid.createdAtMs)))
        }
        when (ParentSessionStore.credentialFailureFor(this@KidsActivity, kid.deviceId)) {
            ParentSessionStore.CredentialFailure.REPAIR_REQUIRED -> {
                append("\n")
                append(getString(R.string.credential_repair_badge))
            }
            ParentSessionStore.CredentialFailure.SECURE_STORAGE_UNAVAILABLE -> {
                append("\n")
                append(getString(R.string.credential_storage_badge))
            }
            null -> Unit
        }
    }

    /**
     * Reconnect to this kid. Passing the stable [PairedChild.deviceId] (not just an IP) is what
     * makes the link permanent: ListenService selects the bearer/pin by deviceId, and autoReconnect
     * falls back to NSD-by-deviceId if the saved IP moved — no QR rescan, no re-auth.
     */
    private fun connect(kid: PairedChild) {
        val failure = ParentSessionStore.credentialFailureFor(this, kid.deviceId)
        if (failure != null) {
            val message = if (failure == ParentSessionStore.CredentialFailure.REPAIR_REQUIRED) {
                R.string.credential_repair_message
            } else {
                R.string.credential_storage_message
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.credential_attention_title)
                .setMessage(message)
                .setPositiveButton(R.string.kidsAddButton) { _, _ ->
                    startActivity(Intent(this, ParentScanActivity::class.java))
                }
                .setNegativeButton(R.string.kidCancel, null)
                .show()
            return
        }
        startActivity(KidConnect.buildIntent(this, kid,
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Toggle this kid as the default Quick-Reconnect / boot target (tap the star). */
    private fun toggleDefault(kid: PairedChild, isCurrentlyDefault: Boolean) {
        PairedChildrenStore.setDefaultKidDeviceId(this, if (isCurrentlyDefault) null else kid.deviceId)
        AppToast.show(this,
            if (isCurrentlyDefault) R.string.kidDefaultCleared else R.string.kidDefaultSetToast,
            Toast.LENGTH_SHORT)
        render()
    }

    private fun rename(kid: PairedChild) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(MAX_NAME_LEN))
            setText(kid.userName ?: "")
            setHint(R.string.kidRenameHint)
            setSelection(text.length)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.kidRenameTitle)
            .setView(wrap)
            .setPositiveButton(R.string.kidRenameSave) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                PairedChildrenStore.setUserName(this, kid.deviceId, name.ifBlank { null })
                AppToast.show(this, R.string.kidSaved, Toast.LENGTH_SHORT)
                render()
            }
            .setNegativeButton(R.string.kidCancel, null)
            .show()
    }

    private fun confirmRemove(kid: PairedChild) {
        AlertDialog.Builder(this)
            .setTitle(R.string.kidRemoveTitle)
            .setMessage(getString(R.string.kidRemoveMessage, kid.displayName))
            .setPositiveButton(R.string.kidRemove) { _, _ ->
                PairedChildrenStore.remove(this, kid.deviceId)
                // Drop the durable credentials too so the app stops renewing for a child we no
                // longer track; the TLS pin is kept (re-pair stays bound to the same device).
                ParentSessionStore.forgetAll(this, kid.deviceId)
                AppToast.show(this, R.string.kidRemoved, Toast.LENGTH_SHORT)
                render()
            }
            .setNegativeButton(R.string.kidCancel, null)
            .show()
    }

    companion object {
        /** Match the child-side name cap (ChildIdentity.setName trims to 40). */
        private const val MAX_NAME_LEN = 40
    }
}
