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
package de.rochefort.childmonitor.privacy

import de.rochefort.childmonitor.TransportPolicy

/** How a privacy fact should read: a reassurance, a caveat the user should know, or neutral info. */
enum class PrivacyLevel { GOOD, CAUTION, INFO }

/** One plain-language, honest statement about the app's privacy posture. */
data class PrivacyFact(
    val title: String,
    val detail: String,
    val level: PrivacyLevel,
)

/** The runtime flags that actually change what is true about data leaving the device. */
data class PrivacyInputs(
    /** The once-a-day GitHub release check (#210). When on, the app makes one outbound call/day. */
    val updateCheckEnabled: Boolean,
    /** Optional event webhooks (#308). When on, chosen events are POSTed to a user-set URL. */
    val eventHooksEnabled: Boolean,
    /** Whether a webhook URL is actually configured. Hooks enabled with NO url makes no internet
     *  call (the dispatcher only fires a local on-device broadcast), so we must not claim egress. */
    val eventHookUrlConfigured: Boolean = false,
)

/**
 * #297: derives the honest privacy summary shown to the user.
 *
 * This is deliberately a pure function with no Android dependencies so it can be unit-tested as the
 * single source of truth for what we claim. The invariants the tests pin down matter: the live
 * audio stream is plaintext on the LAN (#233), so we ALWAYS surface that as a caution and never
 * imply otherwise; and we only claim "no internet" when the update check is actually off.
 */
object PrivacyPosture {

    /** #366: the icon glyph for a privacy level — single source of truth for the friendly screen. */
    fun glyph(level: PrivacyLevel): String = when (level) {
        PrivacyLevel.GOOD -> "✓"
        PrivacyLevel.CAUTION -> "⚠"
        PrivacyLevel.INFO -> "ℹ"
    }

    fun evaluate(inputs: PrivacyInputs): List<PrivacyFact> {
        val facts = mutableListOf<PrivacyFact>()

        facts += PrivacyFact(
            title = "Local network only",
            detail = "Monitoring runs directly between your devices over your Wi‑Fi. " +
                "No account, no sign‑in, and no servers in the middle.",
            level = PrivacyLevel.GOOD,
        )

        facts += PrivacyFact(
            title = "No tracking or telemetry",
            detail = "No analytics, ads, or crash reporting. The app collects nothing about " +
                "you or your baby.",
            level = PrivacyLevel.GOOD,
        )

        facts += PrivacyFact(
            title = "Pairing & control use TLS",
            detail = "Once paired by QR code, pairing and remote control are encrypted with TLS " +
                "(certificate‑pinned). A legacy device without an encryption key falls back to " +
                "cleartext — the app warns you if that happens.",
            level = PrivacyLevel.GOOD,
        )

        // The honest caveat. Audio on port 10000 is authenticated G.711 but NOT TLS-wrapped (#233).
        facts += PrivacyFact(
            title = "Live audio is not encrypted",
            detail = "Audio is authenticated, but streams in the clear on your local network. " +
                "Keep your Wi‑Fi private and avoid untrusted or public networks.",
            level = PrivacyLevel.CAUTION,
        )

        facts += if (inputs.updateCheckEnabled) {
            PrivacyFact(
                title = "Daily update check",
                detail = "Once a day the app asks GitHub whether a newer version exists. " +
                    "No personal data is sent — you can switch this off in Settings.",
                level = PrivacyLevel.INFO,
            )
        } else {
            PrivacyFact(
                title = "No outbound internet",
                detail = "Automatic update checks are off, so the app makes no internet " +
                    "connections of its own.",
                level = PrivacyLevel.GOOD,
            )
        }

        facts += when {
            inputs.eventHooksEnabled && inputs.eventHookUrlConfigured -> PrivacyFact(
                title = "Event webhooks are on",
                detail = "You turned on webhooks, so the events you chose are sent to the web " +
                    "address you set — and nowhere else.",
                level = PrivacyLevel.CAUTION,
            )
            inputs.eventHooksEnabled -> PrivacyFact(
                title = "Event webhooks are on (no address set)",
                detail = "Webhooks are enabled but no web address is configured, so no events are " +
                    "sent over the internet.",
                level = PrivacyLevel.INFO,
            )
            else -> PrivacyFact(
                title = "Event webhooks are off",
                detail = "Webhooks are off, so no events are sent anywhere outside your devices.",
                level = PrivacyLevel.GOOD,
            )
        }

        return facts
    }

    /**
     * #366: the honest privacy fact for THIS connection's control-channel transport, derived from
     * the same [TransportPolicy] decision the request path uses — so the badge can never claim more
     * than what actually goes on the wire. GOOD is reserved strictly for an actively-pinned TLS
     * connection; a lost pin (REFUSE_DOWNGRADE, which fails closed) and a never-pinned cleartext
     * device are both cautions and never described as encrypted.
     */
    fun forConnection(decision: TransportPolicy.Decision): PrivacyFact = when (decision) {
        TransportPolicy.Decision.PINNED_TLS -> PrivacyFact(
            title = "Encrypted (TLS‑pinned)",
            detail = "This connection's control channel is encrypted and pinned to the paired " +
                "child's certificate. (Live audio still streams in the clear — see the privacy summary.)",
            level = PrivacyLevel.GOOD,
        )
        TransportPolicy.Decision.REFUSE_DOWNGRADE -> PrivacyFact(
            title = "Re‑pair required",
            detail = "This child was paired over TLS but its certificate isn't available now, so the " +
                "connection fails closed instead of falling back to cleartext. Re‑pair by QR to restore it.",
            level = PrivacyLevel.CAUTION,
        )
        TransportPolicy.Decision.INSECURE_CLEARTEXT -> PrivacyFact(
            title = "Unencrypted",
            detail = "This child was never paired over TLS, so the control channel is unencrypted on " +
                "your local network. Re‑pair by QR to encrypt it.",
            level = PrivacyLevel.CAUTION,
        )
    }
}
