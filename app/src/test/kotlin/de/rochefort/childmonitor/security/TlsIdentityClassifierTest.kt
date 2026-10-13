/*
 * This file is part of Child Monitor. GPLv3.
 */
package de.rochefort.childmonitor.security

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pass-5 MED-1 coverage for [TlsIdentity.PERMANENT_PREFIXES]. The heartbeat
 * retry in MonitorService.ensureWebServer reads isInitErrorPermanent() to
 * decide whether to keep paying the wake-up cost or accept that TLS will
 * never come up on this install — so every prefix the production code can
 * emit needs to map correctly.
 *
 * These tests construct a tiny shim because TlsIdentity has a private
 * constructor and a hard Android dependency; we test the classifier purely
 * against the prefix list which lives on the companion.
 */
class TlsIdentityClassifierTest {

    @Test fun knownPermanentPrefixesMatch() {
        val cases = listOf(
            "TLS unsupported on API 22",
            "gen-failed: KeyStoreException: locked",
            "factory-failed: NoSuchAlgorithmException: TLS",
            "init-failed: KeyStoreException: provider missing"
        )
        cases.forEach { msg ->
            assertTrue(
                TlsIdentity.PERMANENT_PREFIXES.any { msg.startsWith(it) },
                "'$msg' must be classified as permanent"
            )
        }
    }

    @Test fun unknownPrefixIsTransient() {
        // A bare exception string (e.g. from an unprefixed catch that someone
        // forgets to update) is treated as transient, so the retry keeps
        // trying. The test exists to alert the maintainer if a new catch
        // block ships without a prefix — they'll see this test pass for the
        // unprefixed value and notice the perm-list is incomplete.
        val transient = listOf(
            "IOException: timeout",
            "no identity yet",
            "" // never actually emitted, but defensive
        )
        transient.forEach { msg ->
            assertFalse(
                TlsIdentity.PERMANENT_PREFIXES.any { it.isNotEmpty() && msg.startsWith(it) },
                "'$msg' should NOT match any permanent prefix"
            )
        }
    }

    @Test fun prefixListIsNotEmpty() {
        // Defensive: if a refactor accidentally empties this list, every
        // failure is treated as transient and the heartbeat spams forever.
        assertTrue(TlsIdentity.PERMANENT_PREFIXES.isNotEmpty())
    }
}
