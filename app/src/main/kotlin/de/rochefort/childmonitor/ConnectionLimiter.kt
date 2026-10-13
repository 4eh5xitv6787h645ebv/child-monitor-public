/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.rochefort.childmonitor

/**
 * [#237] Bounds concurrent pre-authentication connection handlers for the raw audio accept
 * loop. The audio port (10000) spawned one thread per accepted socket *before* the CMHELLO
 * handshake, so a connection flood could spawn unbounded threads and starve the
 * safety-critical foreground service. (The WS/HTTP server already had BoundedAsyncRunner;
 * the raw audio listener did not.)
 *
 * Caps both the global number of in-flight handlers and the number from any single IP.
 * Pure (no Android deps) so it is unit-testable.
 */
class ConnectionLimiter(
    private val maxTotal: Int,
    private val maxPerIp: Int,
) {
    private var total = 0
    private val perIp = HashMap<String, Int>()

    /** Try to admit a connection from [ip]; returns false if the global or per-IP cap is hit. */
    @Synchronized
    fun tryAcquire(ip: String): Boolean {
        if (total >= maxTotal) return false
        val current = perIp[ip] ?: 0
        if (current >= maxPerIp) return false
        perIp[ip] = current + 1
        total++
        return true
    }

    /** Release a previously-acquired slot for [ip]. Safe to over-release (never goes negative). */
    @Synchronized
    fun release(ip: String) {
        val current = perIp[ip] ?: return
        when {
            current <= 1 -> perIp.remove(ip)
            else -> perIp[ip] = current - 1
        }
        if (total > 0) total--
    }

    @Synchronized
    fun activeTotal(): Int = total
}
