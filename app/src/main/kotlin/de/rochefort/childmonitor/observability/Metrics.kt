package de.rochefort.childmonitor.observability

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Prometheus-style in-memory metrics.
 * Implements CM-OBS-003: local /metrics endpoint for scrape-based monitoring.
 *
 * Exposes counters and gauges:
 * - cm_audio_bytes_total
 * - cm_audio_packets_total
 * - cm_parents_connected (gauge)
 * - cm_snapshots_served_total
 * - cm_auth_failures_total
 * - cm_reconnect_attempts_total
 * - cm_zombie_sessions_cleaned_total
 * - cm_uptime_seconds (gauge)
 *
 * Snapshot returns a Prometheus-compatible text exposition plus a JSON summary.
 */
object Metrics {

    private val audioBytes = AtomicLong(0)
    private val audioPackets = AtomicLong(0)
    private val snapshotsServed = AtomicLong(0)
    private val authFailures = AtomicLong(0)
    private val reconnectAttempts = AtomicLong(0)
    private val zombiesCleaned = AtomicLong(0)
    private val talkbackBytes = AtomicLong(0)
    private val webSocketConnections = AtomicLong(0)

    fun onAudioSent(bytes: Long) {
        audioBytes.addAndGet(bytes)
        audioPackets.incrementAndGet()
    }

    fun onSnapshotServed() = snapshotsServed.incrementAndGet()
    fun onAuthFailure() = authFailures.incrementAndGet()
    fun onReconnectAttempt() = reconnectAttempts.incrementAndGet()
    fun onZombieCleaned() = zombiesCleaned.incrementAndGet()
    fun onTalkbackBytes(bytes: Long) = talkbackBytes.addAndGet(bytes)
    fun onWebSocketConnected() = webSocketConnections.incrementAndGet()
    fun onWebSocketDisconnected() = webSocketConnections.decrementAndGet()

    fun snapshot(status: JSONObject, sessions: JSONObject): JSONObject {
        val lines = buildString {
            append("# HELP cm_audio_bytes_total Total audio bytes sent to parents\n")
            append("# TYPE cm_audio_bytes_total counter\n")
            append("cm_audio_bytes_total ${audioBytes.get()}\n")
            append("# HELP cm_audio_packets_total Total audio packets sent\n")
            append("# TYPE cm_audio_packets_total counter\n")
            append("cm_audio_packets_total ${audioPackets.get()}\n")
            append("# HELP cm_snapshots_served_total Total snapshot HTTP responses\n")
            append("# TYPE cm_snapshots_served_total counter\n")
            append("cm_snapshots_served_total ${snapshotsServed.get()}\n")
            append("# HELP cm_auth_failures_total Total rejected auth attempts\n")
            append("# TYPE cm_auth_failures_total counter\n")
            append("cm_auth_failures_total ${authFailures.get()}\n")
            append("# HELP cm_reconnect_attempts_total Total reconnect attempts\n")
            append("# TYPE cm_reconnect_attempts_total counter\n")
            append("cm_reconnect_attempts_total ${reconnectAttempts.get()}\n")
            append("# HELP cm_zombie_sessions_cleaned_total Zombie session cleanups\n")
            append("# TYPE cm_zombie_sessions_cleaned_total counter\n")
            append("cm_zombie_sessions_cleaned_total ${zombiesCleaned.get()}\n")
            append("# HELP cm_parents_connected Current parent connection count\n")
            append("# TYPE cm_parents_connected gauge\n")
            append("cm_parents_connected ${status.optInt("connectedParents", 0)}\n")
            append("# HELP cm_uptime_seconds Service uptime\n")
            append("# TYPE cm_uptime_seconds gauge\n")
            append("cm_uptime_seconds ${status.optLong("uptimeSeconds", 0L)}\n")
            append("# HELP cm_ws_connections Current open websocket connections\n")
            append("# TYPE cm_ws_connections gauge\n")
            append("cm_ws_connections ${webSocketConnections.get()}\n")
            append("# HELP cm_sessions_total Currently issued API tokens\n")
            append("# TYPE cm_sessions_total gauge\n")
            append("cm_sessions_total ${sessions.optInt("count", 0)}\n")
        }
        return JSONObject()
            .put("prometheus", lines)
            .put("status", status)
            .put("sessions", sessions)
    }

    fun asJson(): JSONObject {
        return JSONObject()
            .put("audioBytes", audioBytes.get())
            .put("audioPackets", audioPackets.get())
            .put("snapshotsServed", snapshotsServed.get())
            .put("authFailures", authFailures.get())
            .put("reconnectAttempts", reconnectAttempts.get())
            .put("zombiesCleaned", zombiesCleaned.get())
            .put("talkbackBytes", talkbackBytes.get())
            .put("webSocketConnections", webSocketConnections.get())
    }
}
