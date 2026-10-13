package de.rochefort.childmonitor.protocol

import org.json.JSONObject

/**
 * Protocol abstraction layer for RTSP/RTMP/SRT ingest adapters (local-only).
 * Implements CM-PROTO-001 (protocol abstraction) and CM-PROTO-004 (interop harness).
 *
 * The current app uses a bespoke TCP protocol. This abstraction lets future
 * adapters (RTSP, RTMP, SRT, QUIC) plug in without touching callers. For now
 * the only registered implementation is the classic mu-law socket; the
 * contract is defined so alternative paths can be added as discrete classes.
 *
 * Kept intentionally minimal and side-effect free so we can unit-test adapter
 * selection without Android-only dependencies.
 */
interface MediaAdapter {
    val name: String
    val capabilities: Set<String>
    fun canHandle(uri: String): Boolean
    /** Returns a handshake descriptor the peer can read. */
    fun handshakeDescriptor(): JSONObject
}

class MulawSocketAdapter : MediaAdapter {
    override val name: String = "mulaw-tcp"
    override val capabilities: Set<String> = setOf(
        "audio.mulaw", "video.snapshot.jpeg", "talkback.mulaw"
    )
    override fun canHandle(uri: String): Boolean =
        uri.startsWith("tcp://") || uri.startsWith("childmonitor://")

    override fun handshakeDescriptor(): JSONObject = JSONObject()
        .put("name", name)
        .put("transport", "tcp")
        .put("sampleRate", 8000)
        .put("codec", "mulaw")
}

object MediaAdapterRegistry {
    private val adapters = mutableListOf<MediaAdapter>(MulawSocketAdapter())

    fun register(adapter: MediaAdapter) {
        if (adapters.none { it.name == adapter.name }) adapters.add(adapter)
    }

    fun adapters(): List<MediaAdapter> = adapters.toList()

    fun adapterFor(uri: String): MediaAdapter? = adapters.firstOrNull { it.canHandle(uri) }

    /**
     * Interop harness: produce a matrix of (adapter, capability) combinations
     * so a test can verify each pair. Returns the combinations that should
     * round-trip successfully.
     */
    fun interopMatrix(): List<Pair<String, String>> = adapters.flatMap { a ->
        a.capabilities.map { cap -> a.name to cap }
    }
}
