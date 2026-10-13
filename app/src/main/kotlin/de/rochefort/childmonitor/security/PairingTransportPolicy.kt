package de.rochefort.childmonitor.security

/** Pure transport gate for endpoints that create or exchange pairing secrets (#466). */
object PairingTransportPolicy {
    enum class Endpoint { CHALLENGE, COMPLETE, ISSUE_TOKEN }

    /**
     * The challenge contains the child-displayed PIN and is therefore loopback-only. Completion and
     * token issuance may be remote, but only through the TLS listener. In-process child admin flows
     * remain usable over loopback without depending on the TLS listener.
     */
    fun isAllowed(endpoint: Endpoint, isTlsListener: Boolean, isLoopback: Boolean): Boolean =
        when (endpoint) {
            Endpoint.CHALLENGE -> isLoopback
            Endpoint.COMPLETE, Endpoint.ISSUE_TOKEN -> isLoopback || isTlsListener
        }
}
