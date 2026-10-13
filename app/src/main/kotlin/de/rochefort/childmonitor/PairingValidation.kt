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

/**
 * #310/#423: the single source of truth for validating attacker-influenced pairing fields that flow
 * into raw protocol lines (the talkback WebSocket upgrade interpolates host/token verbatim and does
 * NOT strip CRLF). Both [PairingPayload] (the QR boundary) and [PairedChild.fromJson] (which loads
 * persisted/restored pairings — a backup file can be attacker-crafted, #310) validate through here, so
 * a CRLF/header-injection host can never reach the wire via either path. Pure / JVM-testable.
 */
object PairingValidation {

    /** IPv4 / IPv6 / hostname characters only. `%` permits an IPv6 scope id (`%wlan0`);
     *  it is safe in the raw Host header and does not permit CRLF/space/header injection. */
    val SAFE_HOST = Regex("^[A-Za-z0-9._:%\\-]{1,255}$")

    /** SHA-256 fingerprint: 32 colon-separated uppercase/lowercase hex byte groups. */
    val SAFE_FINGERPRINT = Regex("^([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}$")

    /** Bearer token charset — wide enough for URL-safe Base64 / hex, no CRLF/header-injection chars. */
    val SAFE_TOKEN = Regex("^[A-Za-z0-9._\\-]{8,256}$")

    fun isSafeHost(s: String?): Boolean = s != null && SAFE_HOST.matches(s)
    fun isSafeFingerprint(s: String?): Boolean = s != null && SAFE_FINGERPRINT.matches(s)
    fun isSafeToken(s: String?): Boolean = s != null && SAFE_TOKEN.matches(s)
    fun isValidPort(port: Int): Boolean = port in 1..65535
}
