package de.rochefort.childmonitor.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-device token-bucket rate limiter for authentication attempts.
 * Prevents brute-forcing pairing codes.
 *
 * Default: 5 attempts per device per minute, with short lockout on burst.
 */
class AuthRateLimiter(
    private val maxAttempts: Int = 5,
    private val windowMs: Long = 60_000L,
    private val lockoutMs: Long = 5 * 60_000L,
    // #348: cap the number of tracked keys. The public auth endpoints (/api/session/issue,
    // /api/session/refresh) take a caller-supplied deviceId, so an attacker could otherwise grow
    // this map without bound (slow memory DoS). New keys past the cap trigger eviction.
    private val maxKeys: Int = 4096
) {

    private data class Bucket(
        var attempts: Int,
        var windowStartMs: Long,
        var lockoutUntilMs: Long
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    @Synchronized
    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        // #348: bound memory before admitting a brand-new key — evict stale buckets, then (if still
        // full) the oldest, so a flood of unique keys can't grow the map without limit.
        if (!buckets.containsKey(key) && buckets.size >= maxKeys) {
            evictStale(now)
            if (buckets.size >= maxKeys) {
                buckets.entries.minByOrNull { it.value.windowStartMs }?.let { buckets.remove(it.key) }
            }
        }
        val bucket = buckets.getOrPut(key) { Bucket(0, now, 0L) }
        if (now < bucket.lockoutUntilMs) return false
        if (now - bucket.windowStartMs > windowMs) {
            bucket.attempts = 0
            bucket.windowStartMs = now
        }
        bucket.attempts++
        if (bucket.attempts > maxAttempts) {
            bucket.lockoutUntilMs = now + lockoutMs
            return false
        }
        return true
    }

    /** Remove buckets whose window has elapsed and that are not in active lockout. Caller holds the lock. */
    private fun evictStale(now: Long) {
        val stale = buckets.entries
            .filter { now >= it.value.lockoutUntilMs && now - it.value.windowStartMs > windowMs }
            .map { it.key }
        stale.forEach { buckets.remove(it) }
    }

    /** Visible for tests: number of currently-tracked keys. */
    @Synchronized
    fun trackedKeys(): Int = buckets.size

    @Synchronized
    fun reset(key: String) {
        buckets.remove(key)
    }

    @Synchronized
    fun clear() {
        buckets.clear()
    }
}
