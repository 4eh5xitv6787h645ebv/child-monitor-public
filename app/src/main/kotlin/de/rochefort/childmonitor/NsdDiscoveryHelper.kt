package de.rochefort.childmonitor

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Helper class for discovering child devices via NSD (Network Service Discovery).
 * Can be used for initial discovery or as a fallback when saved IPs don't work.
 */
class NsdDiscoveryHelper(private val context: Context) {

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager: WifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var isDiscovering = false
    private val discoveryGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private val handler = Handler(Looper.getMainLooper())
    private val discoveredDevices = mutableListOf<DiscoveredDevice>()

    // Track per-service resolve attempts so we can retry transient failures and stop after MAX.
    // Concurrent because the NSD framework dispatches resolve callbacks on a binder
    // thread while we may read/write entries from the main thread (review #5).
    private val resolveAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val maxResolveAttempts = 3
    private val resolveRetryDelayMs = 600L

    var onDeviceFound: ((DiscoveredDevice) -> Unit)? = null
    var onDiscoveryComplete: ((List<DiscoveredDevice>) -> Unit)? = null
    var onDiscoveryFailed: ((String) -> Unit)? = null
    /**
     * Invoked when a service was found via discovery but could not be resolved after
     * [maxResolveAttempts] tries. The UI can surface "ChildMonitor found but unreachable
     * — check the same network" instead of just showing no devices. (#187 finding 4)
     */
    var onResolveExhausted: ((String) -> Unit)? = null

    data class DiscoveredDevice(
        val name: String,
        val address: String,
        val port: Int,
        /** Stable child device id (from NSD TXT 'id' or parsed from "Name [id]" suffix). */
        val deviceId: String? = null,
        /** Optional pinned-TLS audio port advertised by protocol-v2 children. */
        val audioTlsPort: Int? = null
    )

    /**
     * Start discovering child devices on the network.
     * @param timeoutMs How long to wait for devices before completing (0 = no timeout)
     */
    fun startDiscovery(timeoutMs: Long = 10000L) {
        if (isDiscovering) {
            Log.w(TAG, "Discovery already in progress")
            return
        }

        discoveredDevices.clear()
        resolveAttempts.clear()
        isDiscovering = true
        val generation = discoveryGeneration.incrementAndGet()

        // Acquire multicast lock for mDNS discovery
        try {
            multicastLock = wifiManager.createMulticastLock("NsdDiscoveryHelper")
            multicastLock?.setReferenceCounted(false)
            multicastLock?.acquire()
            Log.d(TAG, "Multicast lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock", e)
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!isCurrentDiscovery(generation)) return
                Log.d(TAG, "Service found: ${service.serviceName}")

                if (service.serviceType == SERVICE_TYPE &&
                    service.serviceName.contains("ChildMonitor")) {
                    // Resolve the service to get IP and port
                    resolveService(service, generation)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (!isCurrentDiscovery(generation)) return
                Log.e(TAG, "Discovery start failed: error $errorCode")
                handler.removeCallbacksAndMessages(null)
                isDiscovering = false
                discoveryListener = null
                releaseMulticastLock()
                onDiscoveryFailed?.invoke("Discovery failed to start (error $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: error $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

            // Set timeout if specified
            if (timeoutMs > 0) {
                handler.postDelayed({
                    completeDiscovery()
                }, timeoutMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            isDiscovering = false
            releaseMulticastLock()
            onDiscoveryFailed?.invoke("Failed to start discovery: ${e.message}")
        }
    }

    private fun resolveService(service: NsdServiceInfo, generation: Int) {
        if (!isCurrentDiscovery(generation)) return
        try {
            NsdResolver.resolve(
                manager = nsdManager,
                service = service,
                executor = java.util.concurrent.Executor { command -> handler.post(command) },
                onResolved = { serviceInfo ->
                    handleResolvedService(serviceInfo, generation)
                },
                onFailure = { errorCode ->
                    handleResolveFailure(service, generation, "error $errorCode")
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve service", e)
            // Synchronous failures (notably an overlapping resolve on older Android releases) used
            // to vanish without retry/exhaustion, so a visible child was reported as zero devices.
            handleResolveFailure(service, generation, e.javaClass.simpleName)
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo, generation: Int) {
        if (!isCurrentDiscovery(generation)) return
        resolveAttempts.remove(serviceInfo.serviceName)
        // API 34+ can return every resolved address. Prefer IPv4 (the app's broadest
        // compatibility path), then non-link-local IPv6, then scoped link-local IPv6.
        val hostAddress = NsdResolver.addresses(serviceInfo)
            .filterNot { it.isLoopbackAddress }
            .sortedBy {
                when {
                    it is java.net.Inet4Address && !it.isLinkLocalAddress -> 0
                    !it.isLinkLocalAddress -> 1
                    it is java.net.Inet6Address -> 2
                    else -> 3
                }
            }
            .firstOrNull()
            ?.hostAddress
            ?: return
        val displayName = serviceInfo.serviceName
            .replace("\\\\032", " ")
            .replace("\\032", " ")
        val txtId = runCatching {
            serviceInfo.attributes?.get("id")?.toString(Charsets.UTF_8)
        }.getOrNull()
        val suffixId = Regex("\\[([0-9a-fA-F]{4,})]")
            .find(displayName)?.groupValues?.getOrNull(1)
        val deviceId = txtId?.takeIf { it.isNotBlank() } ?: suffixId
        val audioTlsPort = runCatching {
            serviceInfo.attributes?.get("ap")?.toString(Charsets.UTF_8)
                ?.toIntOrNull()?.takeIf { it in 1..65535 }
        }.getOrNull()

        val device = DiscoveredDevice(
            name = displayName,
            address = hostAddress,
            port = serviceInfo.port,
            deviceId = deviceId,
            audioTlsPort = audioTlsPort,
        )
        Log.i(TAG, "Resolved device: ${device.name} id=${device.deviceId} at ${device.address}:${device.port}")

        synchronized(discoveredDevices) {
            val duplicate = discoveredDevices.any { existing ->
                if (existing.deviceId != null && device.deviceId != null) {
                    existing.deviceId == device.deviceId
                } else {
                    existing.address == device.address && existing.port == device.port
                }
            }
            if (!duplicate) {
                discoveredDevices.add(device)
                handler.post { onDeviceFound?.invoke(device) }
            }
        }
    }

    private fun handleResolveFailure(service: NsdServiceInfo, generation: Int, reason: String) {
        if (!isCurrentDiscovery(generation)) return
        val serviceName = service.serviceName
        val attempts = synchronized(resolveAttempts) {
            val next = (resolveAttempts[serviceName] ?: 0) + 1
            resolveAttempts[serviceName] = next
            next
        }
        Log.e(TAG, "Resolve failed for $serviceName: $reason (attempt $attempts/$maxResolveAttempts)")
        if (attempts < maxResolveAttempts) {
            // FAILURE_ALREADY_ACTIVE and synchronous overlap errors are transient — serialize via
            // the main handler and back off before trying this service again.
            handler.postDelayed({
                if (isCurrentDiscovery(generation)) resolveService(service, generation)
            }, resolveRetryDelayMs)
        } else {
            Log.w(TAG, "Resolve exhausted for $serviceName after $attempts attempts ($reason)")
            handler.post { onResolveExhausted?.invoke(serviceName) }
        }
    }

    private fun isCurrentDiscovery(generation: Int): Boolean =
        isDiscovering && discoveryGeneration.get() == generation

    /**
     * Stop discovery and return results
     */
    fun stopDiscovery() {
        completeDiscovery()
    }

    private fun completeDiscovery() {
        if (!isDiscovering) return

        // Review-finding #5: cancel any in-flight resolve retries BEFORE flipping
        // isDiscovering=false. Otherwise the retry runnable's "if (isDiscovering)"
        // guard wins the race and we re-enter resolveService against a torn-down
        // helper. Doing this unconditionally is also cheap, so no early-return guard.
        handler.removeCallbacksAndMessages(null)
        resolveAttempts.clear()

        isDiscovering = false

        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping discovery", e)
            }
            discoveryListener = null
        }

        releaseMulticastLock()

        Log.i(TAG, "Discovery complete, found ${discoveredDevices.size} device(s)")
        onDiscoveryComplete?.invoke(discoveredDevices.toList())
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "Multicast lock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing multicast lock", e)
        }
        multicastLock = null
    }

    /**
     * Check if discovery is currently running
     */
    fun isDiscovering(): Boolean = isDiscovering

    companion object {
        private const val TAG = "NsdDiscoveryHelper"
        private const val SERVICE_TYPE = "_childmonitor._tcp."
    }
}
