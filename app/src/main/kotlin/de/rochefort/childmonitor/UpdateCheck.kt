package de.rochefort.childmonitor

import org.json.JSONObject

/**
 * Pure decision logic for the update checker (#210).
 *
 * Deliberately a *check-and-notify* design, not an auto-installer: comparing the
 * installed version against the latest GitHub release and deciding whether to
 * surface a notification is safe and testable; downloading and installing an APK
 * is not done here (it would need REQUEST_INSTALL_PACKAGES and on-device trust
 * decisions). The Android glue in [UpdateChecker] composes these functions.
 */
object UpdateCheck {

    data class Release(val versionName: String, val pageUrl: String)

    /**
     * Parse GitHub's `releases/latest` JSON. Returns the release version (with any
     * leading `v` stripped) and the human page URL, or null if either is missing
     * or the body isn't valid JSON.
     */
    fun parseLatestRelease(json: String): Release? = try {
        val o = JSONObject(json)
        val version = o.optString("tag_name").trim().removePrefix("v").removePrefix("V")
        val url = o.optString("html_url").trim()
        // The url is opened in an ACTION_VIEW intent, so only accept an https
        // github.com release page — never a non-https scheme or arbitrary host
        // from an unexpected/malicious response.
        if (version.isEmpty() || !url.startsWith("https://github.com/")) null else Release(version, url)
    } catch (_: Exception) {
        null
    }

    /** True iff [latest] is a strictly newer dotted version than [current]. Any
     *  non-numeric/malformed input returns false (never prompt on garbage). */
    fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest) ?: return false
        val c = parseVersion(current) ?: return false
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    /** Throttle: allow a check when none has run, when the interval has elapsed, or
     *  when the clock moved backwards (so a bad timestamp can't wedge checking). */
    fun shouldCheck(lastCheckMs: Long, nowMs: Long, minIntervalMs: Long): Boolean {
        if (lastCheckMs <= 0L) return true
        val elapsed = nowMs - lastCheckMs
        return elapsed >= minIntervalMs || elapsed < 0L
    }

    private fun parseVersion(v: String): List<Int>? {
        val cleaned = v.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isEmpty()) return null
        return cleaned.split(".").map { it.toIntOrNull() ?: return null }
    }
}
