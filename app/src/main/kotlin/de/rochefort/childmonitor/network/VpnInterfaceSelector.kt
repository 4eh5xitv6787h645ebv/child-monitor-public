package de.rochefort.childmonitor.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

/**
 * Pin streaming to the active VPN interface and auto-migrate on route/IP change.
 * Implements CM-NET-004: Pin streaming to active VPN interface and auto-migrate.
 *
 * On Android the reliable way to bind a socket to a VPN is via
 * ConnectivityManager.bindProcessToNetwork() or Network.bindSocket(). This
 * class tracks the current VPN network and exposes it through a reactive field.
 * Callers should prefer VpnInterfaceSelector.current() when creating streaming
 * sockets; when it changes, existing sockets should be closed and reopened.
 */
class VpnInterfaceSelector(
    private val context: Context,
    private val listener: (Network?) -> Unit = {}
) {

    private val current = AtomicReference<Network?>(null)
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (current.getAndSet(network) != network) listener(network)
            }
            override fun onLost(network: Network) {
                if (current.compareAndSet(network, null)) listener(null)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    if (current.getAndSet(network) != network) listener(network)
                }
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            callback = cb
        } catch (_: SecurityException) {
            // Some ROMs refuse VPN-specific callbacks without extra perms; fall back
            // to scanning the interface table when asked.
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }

    fun current(): Network? = current.get()

    fun isVpnActive(): Boolean {
        if (current.get() != null) return true
        // start() tracks VPN Networks through callbacks. Interface probing also covers the brief
        // registration/replay window and ROMs that reject VPN-specific callbacks.
        return probeVpnInterface()
    }

    private fun probeVpnInterface(): Boolean {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return false
            var found = false
            for (iface in java.util.Collections.list(ifaces)) {
                if (!iface.isUp) continue
                val n = iface.name.lowercase()
                if (n.startsWith("tun") || n.startsWith("tap") || n.startsWith("ppp") || n.startsWith("wg")) {
                    found = true
                    break
                }
            }
            found
        } catch (_: Exception) {
            false
        }
    }
}
