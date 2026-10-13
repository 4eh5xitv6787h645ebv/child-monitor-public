package de.rochefort.childmonitor

import de.rochefort.childmonitor.LogManager.LogCategory
import de.rochefort.childmonitor.network.NetworkChangePolicy
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Monitors network state changes and logs them for diagnostic purposes.
 * Tracks WiFi connections, disconnections, signal strength changes, and network availability.
 */
class NetworkStateMonitor(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var isMonitoring = false
    private var lastSignalStrength: Int? = null
    private var lastSsid: String? = null
    private var lastIpAddress: String? = null
    private var lastWifiState: Int = Int.MIN_VALUE  // sentinel — never matches a real WIFI_STATE_*

    // Threshold for logging RSSI changes - only log if change is > this value
    private val rssiChangeThreshold = 10  // dBm

    // Valid RSSI range for filtering sentinel values (Integer.MIN_VALUE during handoff)
    private val MIN_VALID_RSSI = -127  // dBm — anything weaker is "no signal"
    private val MAX_VALID_RSSI = -10   // dBm — anything stronger is implausible/sentinel

    /**
     * Fired when the OS reports a newly-usable network. Subscribers (e.g. ListenService) use this
     * to break out of backoff sleeps and retry immediately instead of waiting up to 60s.
     * Networks already present when monitoring starts are suppressed individually; this avoids the
     * old "skip the first callback" bug where a service started offline skipped the REAL restore.
     */
    var onNetworkAvailable: (() -> Unit)? = null

    /**
     * Fired when the device's local IP set MEANINGFULLY changes on a STILL-connected
     * network (DHCP renew / AP roam) — the case onAvailable does NOT cover. Subscribers
     * (MonitorService) use this to refresh the NSD advertisement so the parent isn't left
     * chasing a stale address. The audio
     * ServerSocket is wildcard-bound, so only the advertisement needs refreshing.
     */
    var onIpAddressChanged: (() -> Unit)? = null
    private val networkStateLock = Any()
    private val lastLinkAddresses = mutableMapOf<Network, Set<String>>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val transportType = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                else -> "Unknown"
            }
            LogManager.logNetwork("Network available", details = "Transport: $transportType")
            logCurrentNetworkState()
            synchronized(networkStateLock) {
                val initialAddresses = connectivityManager.getLinkProperties(network)?.linkAddresses
                    ?.mapNotNull { it.address?.hostAddress }
                    ?.let(NetworkChangePolicy::relevant)
                    .orEmpty()
                if (initialAddresses.isNotEmpty()) lastLinkAddresses[network] = initialAddresses
            }
            try {
                onNetworkAvailable?.invoke()
            } catch (e: Exception) {
                LogManager.e(LogCategory.NETWORK, "onNetworkAvailable callback failed", e)
            }
        }

        override fun onLost(network: Network) {
            LogManager.e(LogCategory.NETWORK, "Network lost - connection may be affected")
            lastSsid = null
            lastIpAddress = null
            lastSignalStrength = null
            synchronized(networkStateLock) {
                // If a snapshotted network disappears before its initial onAvailable callback,
                // it must not suppress a later genuine restoration of that Network.
                lastLinkAddresses.remove(network)
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val signalStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                capabilities.signalStrength
            } else null

            // Only log significant signal strength changes to reduce log noise.
            // Discard sentinel values: Android returns Integer.MIN_VALUE (or values
            // outside the valid -127..0 dBm range) during disassociation/handoff.
            // Without this guard the delta calculation overflows — we observed log
            // lines like "WiFi signal degraded by 2147483601dBm" on 2026-04-14.
            if (signalStrength != null && signalStrength in MIN_VALID_RSSI..MAX_VALID_RSSI) {
                val lastRssi = lastSignalStrength
                if (lastRssi == null || kotlin.math.abs(signalStrength - lastRssi) > rssiChangeThreshold) {
                    val change = if (lastRssi != null) {
                        val diff = signalStrength - lastRssi
                        when {
                            diff > rssiChangeThreshold -> "improved by ${diff}dBm"
                            diff < -rssiChangeThreshold -> "degraded by ${-diff}dBm"
                            else -> "changed"
                        }
                    } else "detected"

                    val quality = calculateSignalQuality(signalStrength)
                    LogManager.logNetwork(
                        "WiFi signal $change",
                        signalStrength = signalStrength,
                        details = "Previous: ${lastRssi ?: "N/A"}dBm, Quality: $quality"
                    )
                    lastSignalStrength = signalStrength
                }
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // A same-network IP change (DHCP renew / AP roam) fires here but NOT onAvailable.
            // The stale IP only lives in the NSD advertisement (the audio ServerSocket is
            // wildcard-bound), so on a meaningful change we re-advertise via onIpAddressChanged.
            val relevantNow = NetworkChangePolicy.relevant(
                linkProperties.linkAddresses.mapNotNull { it.address?.hostAddress }
            )
            // Track each Network independently. A global address set confuses simultaneous Wi-Fi /
            // cellular callbacks and can report a transport switch as a same-link IP change.
            // Do not debounce a real change: Android does not guarantee another link-properties
            // callback, so dropping the only A->B event leaves stale NSD forever.
            val decision = synchronized(networkStateLock) {
                val d = NetworkChangePolicy.decideLinkChange(lastLinkAddresses[network].orEmpty(), relevantNow)
                lastLinkAddresses[network] = d.nextLastAddresses
                d
            }
            if (!decision.fire) return
            LogManager.i(LogCategory.NETWORK, "Local IP address changed on connected network")
            try {
                onIpAddressChanged?.invoke()
            } catch (e: Exception) {
                LogManager.e(LogCategory.NETWORK, "onIpAddressChanged callback failed", e)
            }
        }
    }

    // Sparse Wi-Fi state receiver. RSSI_CHANGED_ACTION used to be subscribed here but it
    // fires every few seconds while Wi-Fi is active, which burnt CPU on every onReceive
    // even when filtered down to "only log >10 dBm changes." The NetworkCallback's
    // onCapabilitiesChanged() already delivers signal strength as part of normal capability
    // updates (debounced by the platform), so we don't lose any signal-quality observability
    // by dropping the broadcast.
    //
    // NETWORK_STATE_CHANGED_ACTION (legacy) is also dropped — the NetworkCallback's
    // onAvailable/onLost/onCapabilitiesChanged together cover the same information without
    // the broadcast firehose.
    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    val stateStr = when (state) {
                        WifiManager.WIFI_STATE_ENABLED -> "ENABLED"
                        WifiManager.WIFI_STATE_DISABLED -> "DISABLED"
                        WifiManager.WIFI_STATE_ENABLING -> "ENABLING"
                        WifiManager.WIFI_STATE_DISABLING -> "DISABLING"
                        else -> "UNKNOWN"
                    }
                    // De-duplicate redundant "no change" transitions and only log
                    // genuinely-abnormal (disabled/unknown) states at WARNING. ENABLED
                    // / ENABLING / DISABLING are normal lifecycle noise.
                    if (state == lastWifiState) return
                    lastWifiState = state
                    when (state) {
                        WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_UNKNOWN ->
                            LogManager.w(LogCategory.NETWORK, "WiFi state changed to: $stateStr")
                        else ->
                            LogManager.i(LogCategory.NETWORK, "WiFi state changed to: $stateStr")
                    }

                    if (state == WifiManager.WIFI_STATE_DISABLED) {
                        lastSsid = null
                        lastIpAddress = null
                        lastSignalStrength = null
                        synchronized(networkStateLock) {
                            lastLinkAddresses.clear()
                        }
                    }
                }
            }
        }
    }

    /**
     * Start monitoring network state
     */
    fun startMonitoring() {
        if (isMonitoring) return

        LogManager.i(LogCategory.NETWORK, "Starting network monitoring")

        // Register network callback for modern Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()

            try {
                // Existing matching networks are replayed through onAvailable. Treating that replay
                // as an availability signal is harmless and avoids deprecated, racy snapshots.
                connectivityManager.registerNetworkCallback(request, networkCallback)
            } catch (e: Exception) {
                LogManager.e(LogCategory.NETWORK, "Failed to register network callback", e)
            }
        }

        // Register only the sparse on/off action. RSSI_CHANGED_ACTION and the legacy
        // NETWORK_STATE_CHANGED_ACTION are deliberately not subscribed — they fire every
        // few seconds and the same data is delivered through NetworkCallback above.
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        try {
            context.registerReceiver(wifiStateReceiver, filter)
        } catch (e: Exception) {
            LogManager.e(LogCategory.NETWORK, "Failed to register WiFi receiver", e)
        }

        isMonitoring = true
        logCurrentNetworkState()
    }

    /**
     * Stop monitoring network state
     */
    fun stopMonitoring() {
        if (!isMonitoring) return

        LogManager.i(LogCategory.NETWORK, "Stopping network monitoring")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            }
        } catch (e: Exception) {
            LogManager.e(LogCategory.NETWORK, "Failed to unregister network callback", e)
        }

        try {
            context.unregisterReceiver(wifiStateReceiver)
        } catch (e: Exception) {
            LogManager.e(LogCategory.NETWORK, "Failed to unregister WiFi receiver", e)
        }

        isMonitoring = false
        synchronized(networkStateLock) {
            lastLinkAddresses.clear()
        }
    }

    /**
     * Log current network state with all available details
     */
    fun logCurrentNetworkState() {
        try {
            val wifiInfo = getWifiInfo()
            // #136: Android 10+ returns "<unknown ssid>" (literal placeholder)
            // when ACCESS_FINE_LOCATION isn't granted or location services are
            // off. Logging "<unknown ssid>" everywhere is noise and makes the
            // diagnostic log misleading — substitute a more actionable label.
            // The full opt-in toggle is deferred to a follow-up UX pass; this
            // is the no-permission-required improvement.
            val rawSsid = wifiInfo?.ssid?.removeSurrounding("\"")
            val ssid = when {
                rawSsid.isNullOrBlank() -> "Not connected"
                rawSsid.equals("<unknown ssid>", ignoreCase = true) ->
                    "SSID hidden (enable location to log)"
                // #206: never persist the real network name — it identifies the
                // home, and the diagnostic log can be exported/shared.
                else -> LogRedaction.redactSsid(rawSsid)
            }
            val ipAddress = getIpAddress()
            val signalStrength = wifiInfo?.rssi ?: -200

            // Check if values have changed
            val ssidChanged = ssid != lastSsid
            val ipChanged = ipAddress != lastIpAddress

            if (ssidChanged || ipChanged || lastSsid == null) {
                val details = buildString {
                    // #206: mask the host octet so the shared log doesn't carry the
                    // device's full address. lastIpAddress keeps the real value for
                    // change detection (above).
                    append("IP: ${LogRedaction.redactIp(ipAddress)}")
                    if (wifiInfo != null) {
                        append(" | Link Speed: ${wifiInfo.linkSpeed} Mbps")
                        append(" | Frequency: ${getFrequency(wifiInfo)} GHz")
                        append(" | Signal Quality: ${calculateSignalQuality(signalStrength)}")
                    }
                }

                LogManager.logNetwork(
                    event = if (ssidChanged) "WiFi network changed" else "Network state update",
                    ssid = ssid,
                    signalStrength = if (signalStrength != -200) signalStrength else null,
                    details = details
                )

                lastSsid = ssid
                lastIpAddress = ipAddress
                lastSignalStrength = signalStrength
            }
        } catch (e: Exception) {
            LogManager.e(LogCategory.NETWORK, "Failed to log network state", e)
        }
    }

    /**
     * Get WiFi connection info
     */
    @Suppress("DEPRECATION")
    private fun getWifiInfo(): WifiInfo? {
        return try {
            if (wifiManager.isWifiEnabled) {
                wifiManager.connectionInfo
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get device IP address
     */
    private fun getIpAddress(): String {
        return try {
            val wifiInfo = getWifiInfo()
            if (wifiInfo != null) {
                @Suppress("DEPRECATION")
                val ip = wifiInfo.ipAddress
                WireFormat.littleEndianIpv4(ip)
            } else {
                "No IP"
            }
        } catch (e: Exception) {
            "Error"
        }
    }

    /**
     * Get WiFi frequency (2.4 or 5 GHz)
     */
    private fun getFrequency(wifiInfo: WifiInfo): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val freq = wifiInfo.frequency
                when {
                    freq in 2400..2500 -> "2.4"
                    freq in 5000..5900 -> "5"
                    else -> "Unknown"
                }
            } else {
                "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Calculate signal quality from RSSI
     */
    private fun calculateSignalQuality(rssi: Int): String {
        return when {
            rssi >= -50 -> "Excellent"
            rssi >= -60 -> "Good"
            rssi >= -70 -> "Fair"
            rssi >= -80 -> "Weak"
            else -> "Very Weak"
        }
    }
}
