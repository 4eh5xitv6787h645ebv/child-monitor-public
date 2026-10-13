package de.rochefort.childmonitor.network

import java.net.InetAddress
import java.net.Socket
import kotlin.math.min

/**
 * VPN MTU probing and fragmentation-safe packet sizing.
 * Implements CM-NET-008: VPN MTU probing and fragmentation-safe packet sizing.
 *
 * The TCP sockets used for audio streaming benefit from chunk sizes that fit
 * comfortably inside path MTU minus VPN/TCP overhead. Android does not expose
 * DF probing directly, so we use a conservative guess per interface type plus
 * an outbound payload ceiling applied by the streamer.
 */
object MtuProbe {

    const val DEFAULT_ETHERNET_MTU = 1500
    const val VPN_CONSERVATIVE_MTU = 1380
    const val CELLULAR_MTU = 1420
    const val MIN_SAFE_MTU = 576

    data class Recommendation(
        val pathMtuBytes: Int,
        val maxPayloadBytes: Int
    )

    fun recommend(isVpn: Boolean, isCellular: Boolean): Recommendation {
        val mtu = when {
            isVpn -> VPN_CONSERVATIVE_MTU
            isCellular -> CELLULAR_MTU
            else -> DEFAULT_ETHERNET_MTU
        }
        // Reserve 40 bytes for IP+TCP headers, 20 bytes for VPN encapsulation.
        val reservation = if (isVpn) 60 else 40
        val payload = (mtu - reservation).coerceAtLeast(MIN_SAFE_MTU - 40)
        return Recommendation(mtu, payload)
    }

    /**
     * Caps a desired write chunk size to the recommendation for the current path.
     */
    fun chunkForWrite(desiredBytes: Int, rec: Recommendation): Int =
        min(desiredBytes, rec.maxPayloadBytes)

    /**
     * Setting small SO_SNDBUF and disabling Nagle helps real-time streams stay
     * below the MTU boundary; the caller still needs to respect `chunkForWrite`.
     */
    fun tuneSocket(socket: Socket, isVpn: Boolean) {
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = if (isVpn) 64 * 1024 else 128 * 1024
            socket.keepAlive = true
        } catch (_: Exception) {
            // Socket options are advisory.
        }
    }

    /**
     * Simple reachability test to detect MTU black-holing heuristically by
     * walking payload sizes downward. Returns the largest payload that round-trips.
     */
    fun binarySearchMaxPayload(
        host: InetAddress,
        port: Int,
        tester: (Int) -> Boolean,
        low: Int = 512,
        high: Int = 1420
    ): Int {
        var lo = low
        var hi = high
        var best = lo
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (tester(mid)) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }
}
