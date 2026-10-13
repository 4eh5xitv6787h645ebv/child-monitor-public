package de.rochefort.childmonitor.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * API versioning and backward compatibility contract.
 * Implements CM-PROTO-002: API versioning and backward compatibility contract.
 * Implements CM-PROTO-003: Capability negotiation at connect.
 *
 * A three-part version tuple (major.minor.patch). Major bumps break the wire
 * format; minor bumps add backward-compatible fields; patch bumps are bugfixes.
 * Capabilities are a flat string set the peers advertise at handshake.
 */
data class ProtocolVersion(val major: Int, val minor: Int, val patch: Int) {
    override fun toString(): String = "$major.$minor.$patch"

    fun isCompatibleWith(other: ProtocolVersion): Boolean = this.major == other.major

    companion object {
        val CURRENT = ProtocolVersion(1, 2, 0)
        val MIN_SUPPORTED = ProtocolVersion(1, 0, 0)

        fun parse(s: String?): ProtocolVersion? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size < 2) return null
            return ProtocolVersion(parts[0], parts.getOrNull(1) ?: 0, parts.getOrNull(2) ?: 0)
        }
    }
}

object Capabilities {
    const val AUDIO_MULAW = "audio.mulaw"
    const val AUDIO_OPUS = "audio.opus"
    const val SNAPSHOT_JPEG = "video.snapshot.jpeg"
    const val LIVE_H264 = "video.live.h264"
    const val TALKBACK_MULAW = "talkback.mulaw"
    const val TALKBACK_OPUS = "talkback.opus"
    const val EVENTS_PUSH = "events.push"
    const val TIMELINE = "timeline.query"
    const val METRICS_SCRAPE = "metrics.prometheus"
    const val PAIRING_PIN = "pairing.pin"
    const val PAIRING_QR = "pairing.qr"
    const val AUTH_BEARER = "auth.bearer"

    fun ours(): Set<String> = setOf(
        AUDIO_MULAW, SNAPSHOT_JPEG, TALKBACK_MULAW,
        EVENTS_PUSH, TIMELINE, METRICS_SCRAPE,
        PAIRING_PIN, PAIRING_QR, AUTH_BEARER
    )
}

object CapabilityHandshake {
    fun handshakeOffer(version: ProtocolVersion = ProtocolVersion.CURRENT): JSONObject {
        val caps = JSONArray()
        for (c in Capabilities.ours()) caps.put(c)
        return JSONObject()
            .put("version", version.toString())
            .put("minVersion", ProtocolVersion.MIN_SUPPORTED.toString())
            .put("capabilities", caps)
    }

    fun intersect(peer: JSONObject): Set<String> {
        val result = mutableSetOf<String>()
        val ours = Capabilities.ours()
        val arr = peer.optJSONArray("capabilities") ?: return emptySet()
        for (i in 0 until arr.length()) {
            val cap = arr.optString(i)
            if (cap.isNotBlank() && cap in ours) result.add(cap)
        }
        return result
    }

    fun isPeerCompatible(peer: JSONObject): Boolean {
        val versionString = peer.optString("version").takeIf { it.isNotBlank() } ?: return false
        val peerVersion = ProtocolVersion.parse(versionString) ?: return false
        return peerVersion.isCompatibleWith(ProtocolVersion.CURRENT)
    }
}
