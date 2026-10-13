package de.rochefort.childmonitor

import de.rochefort.childmonitor.privacy.PrivacyInputs
import de.rochefort.childmonitor.privacy.PrivacyLevel
import de.rochefort.childmonitor.privacy.PrivacyPosture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #297: the privacy summary must be HONEST. The whole point of these tests is to make it
 * impossible to silently start claiming the live audio is encrypted (it is plaintext on the
 * LAN, #233) or that the app makes no internet connections when the update check is on.
 */
class PrivacyPostureTest {

    private fun factsFor(update: Boolean, hooks: Boolean, urlConfigured: Boolean = false) =
        PrivacyPosture.evaluate(
            PrivacyInputs(
                updateCheckEnabled = update,
                eventHooksEnabled = hooks,
                eventHookUrlConfigured = urlConfigured,
            )
        )

    @Test
    fun alwaysAffirmsLocalOnlyAndNoTelemetry() {
        for (u in listOf(true, false)) for (h in listOf(true, false)) {
            val facts = factsFor(u, h)
            assertTrue(
                "local-only fact missing for update=$u hooks=$h",
                facts.any { it.level == PrivacyLevel.GOOD && it.title.contains("Local", ignoreCase = true) }
            )
            assertTrue(
                "no-telemetry fact missing for update=$u hooks=$h",
                facts.any {
                    it.level == PrivacyLevel.GOOD &&
                        (it.title.contains("telemetry", true) || it.title.contains("tracking", true))
                }
            )
        }
    }

    @Test
    fun alwaysWarnsThatLiveAudioIsNotEncrypted_neverClaimsOtherwise() {
        for (u in listOf(true, false)) for (h in listOf(true, false)) {
            val facts = factsFor(u, h)
            val audio = facts.singleOrNull { it.title.contains("audio", ignoreCase = true) }
            assertTrue("expected exactly one audio fact (update=$u hooks=$h)", audio != null)
            // The audio fact must be a CAUTION — never GOOD/INFO that downplays it.
            assertEquals(PrivacyLevel.CAUTION, audio!!.level)
            // And no fact anywhere may assert the audio IS encrypted.
            val lies = facts.any {
                val t = (it.title + " " + it.detail).lowercase()
                t.contains("audio") && t.contains("encrypted") && !t.contains("not encrypted")
            }
            assertFalse("a fact claims the audio is encrypted (update=$u hooks=$h)", lies)
        }
    }

    @Test
    fun updateCheckOn_disclosesTheDailyGithubCall() {
        val facts = factsFor(update = true, hooks = false)
        assertTrue(
            "update-on must disclose the daily check",
            facts.any { it.title.contains("update", true) && it.level == PrivacyLevel.INFO }
        )
        // Must NOT claim zero outbound internet while the check is on.
        assertFalse(
            "must not claim no-internet while update check is on",
            facts.any { it.detail.contains("no internet", true) || it.detail.contains("no outbound", true) }
        )
    }

    @Test
    fun updateCheckOff_claimsNoOutboundInternet() {
        val facts = factsFor(update = false, hooks = false)
        assertTrue(
            "update-off should affirm no outbound internet",
            facts.any { it.level == PrivacyLevel.GOOD && it.detail.contains("no internet", true) }
        )
        assertFalse(
            "update-off must not still advertise a daily check",
            facts.any { it.title.contains("Daily update", true) }
        )
    }

    @Test
    fun hooksOnWithUrl_warnsThatEventsLeaveToUserUrl() {
        val facts = factsFor(update = false, hooks = true, urlConfigured = true)
        val hook = facts.singleOrNull { it.title.contains("webhook", true) || it.title.contains("hook", true) }
        assertTrue("hooks-on must surface a webhook fact", hook != null)
        assertEquals("hooks-on+url egress must be a CAUTION", PrivacyLevel.CAUTION, hook!!.level)
    }

    @Test
    fun hooksOnWithoutUrl_doesNotClaimInternetEgress() {
        // Hooks enabled but no URL: the dispatcher only fires a local broadcast — nothing leaves
        // over the internet, so we must NOT claim events are sent to a web address.
        val facts = factsFor(update = false, hooks = true, urlConfigured = false)
        val hook = facts.single { it.title.contains("webhook", true) || it.title.contains("hook", true) }
        assertEquals(PrivacyLevel.INFO, hook.level)
        assertFalse(
            "no-url hooks must not claim egress to a web address",
            facts.any { it.detail.contains("sent to the web address", true) }
        )
    }

    @Test
    fun hooksOff_affirmsNothingLeaves() {
        val facts = factsFor(update = false, hooks = false)
        val hook = facts.singleOrNull { it.title.contains("webhook", true) || it.title.contains("hook", true) }
        assertTrue("hooks-off should still state webhooks are off", hook != null)
        assertEquals(PrivacyLevel.GOOD, hook!!.level)
    }

    @Test
    fun forConnection_goodLevelReservedForPinnedTls() {
        // #366 honesty: a per-connection badge may only read GOOD when the connection is actively
        // TLS-pinned. A lost pin (fails closed) and a never-pinned cleartext device are cautions.
        for (d in TransportPolicy.Decision.values()) {
            val fact = PrivacyPosture.forConnection(d)
            assertEquals(
                "GOOD must be reserved for PINNED_TLS (decision=$d)",
                d == TransportPolicy.Decision.PINNED_TLS,
                fact.level == PrivacyLevel.GOOD,
            )
            assertTrue("blank title for $d", fact.title.isNotBlank())
            assertTrue("blank detail for $d", fact.detail.isNotBlank())
        }
    }

    @Test
    fun forConnection_pinnedAffirmsEncrypted_cleartextNeverDoes() {
        val pinned = PrivacyPosture.forConnection(TransportPolicy.Decision.PINNED_TLS)
        assertTrue(
            "pinned fact should affirm encryption",
            (pinned.title + " " + pinned.detail).contains("encrypted", ignoreCase = true),
        )
        val clear = PrivacyPosture.forConnection(TransportPolicy.Decision.INSECURE_CLEARTEXT)
        assertTrue(
            "cleartext fact must say unencrypted",
            (clear.title + " " + clear.detail).contains("unencrypted", ignoreCase = true),
        )
        val clearText = (clear.title + " " + clear.detail).lowercase()
        assertFalse(
            "cleartext fact must not affirmatively claim the channel is encrypted",
            clearText.contains("is encrypted") || clearText.contains("encrypted and pinned"),
        )
    }

    @Test
    fun glyph_isDistinctNonBlankPerLevel() {
        // #366: the friendly screen keys its icon off this — every level must map to a distinct glyph.
        val glyphs = PrivacyLevel.values().map { PrivacyPosture.glyph(it) }
        assertTrue("glyphs must be non-blank", glyphs.all { it.isNotBlank() })
        assertEquals("glyphs must be distinct per level", glyphs.size, glyphs.toSet().size)
    }

    @Test
    fun everyFactHasNonEmptyTitleAndDetail() {
        val facts = factsFor(update = true, hooks = true)
        assertTrue(facts.isNotEmpty())
        for (f in facts) {
            assertTrue("empty title", f.title.isNotBlank())
            assertTrue("empty detail for '${f.title}'", f.detail.isNotBlank())
        }
    }
}
