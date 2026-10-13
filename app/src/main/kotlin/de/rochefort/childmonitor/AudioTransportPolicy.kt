package de.rochefort.childmonitor

/** Pure rollout/fail-closed policy for the dedicated audio socket (#233). */
object AudioTransportPolicy {
    enum class Decision { PINNED_TLS, LEGACY_CLEARTEXT, REFUSE_DOWNGRADE }

    fun decide(hasPin: Boolean, audioTlsPort: Int?, hasEverAudioTls: Boolean): Decision = when {
        hasPin && audioTlsPort != null && audioTlsPort in 1..65535 -> Decision.PINNED_TLS
        hasEverAudioTls -> Decision.REFUSE_DOWNGRADE
        else -> Decision.LEGACY_CLEARTEXT
    }

    /** Child-side counterpart: strict sessions are accepted only on the TLS listener. */
    fun acceptsSession(tlsAudioRequired: Boolean, secureTransport: Boolean): Boolean =
        !tlsAudioRequired || secureTransport
}
