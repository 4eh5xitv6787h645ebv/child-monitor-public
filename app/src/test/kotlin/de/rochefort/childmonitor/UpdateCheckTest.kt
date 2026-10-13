package de.rochefort.childmonitor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {

    // --- isNewer ---

    @Test fun `a higher patch, minor or major is newer`() {
        assertTrue(UpdateCheck.isNewer("3.3.10", "3.3.9"))
        assertTrue(UpdateCheck.isNewer("3.4.0", "3.3.9"))
        assertTrue(UpdateCheck.isNewer("4.0.0", "3.9.9"))
    }

    @Test fun `equal or older is not newer`() {
        assertFalse(UpdateCheck.isNewer("3.3.9", "3.3.9"))
        assertFalse(UpdateCheck.isNewer("3.3.8", "3.3.9"))
        assertFalse(UpdateCheck.isNewer("3.2.0", "3.3.9"))
    }

    @Test fun `the v prefix is ignored`() {
        assertTrue(UpdateCheck.isNewer("v3.3.10", "3.3.9"))
        assertFalse(UpdateCheck.isNewer("v3.3.9", "3.3.9"))
    }

    @Test fun `differing segment counts compare by padding with zero`() {
        assertFalse(UpdateCheck.isNewer("3.3", "3.3.0"))
        assertTrue(UpdateCheck.isNewer("3.3.1", "3.3"))
        assertTrue(UpdateCheck.isNewer("4", "3.9.9"))
    }

    @Test fun `non-numeric versions are never treated as newer`() {
        assertFalse(UpdateCheck.isNewer("abc", "3.3.9"))
        assertFalse(UpdateCheck.isNewer("3.3.9", "abc"))
        assertFalse(UpdateCheck.isNewer("3.x.9", "3.3.9"))
    }

    // --- parseLatestRelease ---

    @Test fun `parses tag and url from the github latest-release json`() {
        val json = """{"tag_name":"v3.4.0","html_url":"https://github.com/o/r/releases/tag/v3.4.0","name":"v3.4.0"}"""
        val r = UpdateCheck.parseLatestRelease(json)!!
        assertEquals("3.4.0", r.versionName)   // v stripped
        assertEquals("https://github.com/o/r/releases/tag/v3.4.0", r.pageUrl)
    }

    @Test fun `missing tag or url yields null`() {
        assertNull(UpdateCheck.parseLatestRelease("""{"html_url":"https://github.com/o/r"}"""))
        assertNull(UpdateCheck.parseLatestRelease("""{"tag_name":"v3.4.0"}"""))
        assertNull(UpdateCheck.parseLatestRelease("not json"))
        assertNull(UpdateCheck.parseLatestRelease("{}"))
    }

    @Test fun `a non-github or non-https url is rejected`() {
        // Defense-in-depth: the url is opened in an intent, so reject anything
        // that isn't an https github.com page.
        assertNull(UpdateCheck.parseLatestRelease("""{"tag_name":"v9","html_url":"http://github.com/o/r"}"""))
        assertNull(UpdateCheck.parseLatestRelease("""{"tag_name":"v9","html_url":"https://evil.example/x"}"""))
        assertNull(UpdateCheck.parseLatestRelease("""{"tag_name":"v9","html_url":"javascript:alert(1)"}"""))
    }

    // --- shouldCheck (throttle) ---

    @Test fun `first check (no prior timestamp) is allowed`() {
        assertTrue(UpdateCheck.shouldCheck(lastCheckMs = 0L, nowMs = 1_000_000L, minIntervalMs = 86_400_000L))
    }

    @Test fun `check is throttled within the interval and allowed after it`() {
        val day = 86_400_000L
        assertFalse(UpdateCheck.shouldCheck(lastCheckMs = 1_000L, nowMs = 1_000L + day / 2, minIntervalMs = day))
        assertTrue(UpdateCheck.shouldCheck(lastCheckMs = 1_000L, nowMs = 1_000L + day, minIntervalMs = day))
    }

    @Test fun `a future or corrupt last-check timestamp does not wedge checking`() {
        // nowMs < lastCheckMs (clock moved back) should not permanently disable checks.
        assertTrue(UpdateCheck.shouldCheck(lastCheckMs = 10_000_000L, nowMs = 1_000L, minIntervalMs = 86_400_000L))
    }
}
