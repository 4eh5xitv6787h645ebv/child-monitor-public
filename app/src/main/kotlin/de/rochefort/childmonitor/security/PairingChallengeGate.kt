/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.rochefort.childmonitor.security

import java.security.MessageDigest

/**
 * Pure, in-memory pairing-challenge state machine with a brute-force attempt cap.
 *
 * [#232] The 6-digit pairing PIN has only ~1e6 possibilities and a 2-minute TTL. Without
 * an attempt cap a LAN peer could hammer `/api/pairing/complete` (or `/api/session/issue`)
 * and guess the live PIN; the previous per-deviceId rate limiter was bypassable by
 * spoofing the attacker-supplied `deviceId`. This gate counts every verification against
 * the active challenge and burns it after [maxAttempts] failures (and on the first
 * success), so total guesses per challenge are bounded no matter how the caller varies
 * deviceId/IP. With the default cap of 5, success probability per challenge is ~5e-6.
 *
 * No Android dependencies, so it is fully unit-testable. [now] is injectable so expiry can
 * be tested deterministically.
 */
class PairingChallengeGate(
    private val maxAttempts: Int = 5,
    private val now: () -> Long = System::currentTimeMillis,
) {

    enum class Verdict { OK, WRONG_CODE, NO_CHALLENGE, EXPIRED }

    private class Active(val code: String, val expiresAtMs: Long) {
        var attempts: Int = 0
    }

    private var active: Active? = null

    /** Arm a new challenge, replacing any previous one. */
    @Synchronized
    fun start(code: String, expiresAtMs: Long) {
        active = Active(code, expiresAtMs)
    }

    /** True iff a non-expired challenge is currently armed (expired ones are cleared). */
    @Synchronized
    fun hasActive(): Boolean {
        val a = active ?: return false
        if (now() > a.expiresAtMs) {
            active = null
            return false
        }
        return true
    }

    /** Explicitly drop the active challenge. */
    @Synchronized
    fun invalidate() {
        active = null
    }

    /**
     * Verify [code] against the active challenge. Each call consumes one attempt; after
     * [maxAttempts] failed attempts the challenge is burned (subsequent calls see
     * [Verdict.NO_CHALLENGE]). A correct code also burns the challenge so it cannot be
     * replayed.
     */
    @Synchronized
    fun verify(code: String): Verdict {
        val a = active ?: return Verdict.NO_CHALLENGE
        if (now() > a.expiresAtMs) {
            active = null
            return Verdict.EXPIRED
        }
        a.attempts++
        if (!constantTimeEquals(code, a.code)) {
            if (a.attempts >= maxAttempts) active = null
            return Verdict.WRONG_CODE
        }
        active = null
        return Verdict.OK
    }

    companion object {
        /** Length-independent constant-time compare to avoid leaking the PIN via timing. */
        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
