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
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor

import android.app.Activity
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.DiscoveryListener
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.core.content.ContextCompat

class DiscoverActivity : Activity() {
    private lateinit var nsdManager: NsdManager
    private var discoveryListener: DiscoveryListener? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "ChildMonitor start")
        this.nsdManager = this.getSystemService(NSD_SERVICE) as NsdManager
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discover)
        ThemeManager.applyTheme(this)
        // Primary action: scan the child's QR. Fastest, no IP typing.
        findViewById<Button>(R.id.scanQrButton)?.setOnClickListener {
            startActivity(Intent(this, ParentScanActivity::class.java))
        }
        val discoverChildButton = findViewById<Button>(R.id.discoverChildButton)
        discoverChildButton.setOnClickListener { _: View? -> loadDiscoveryViaMdns() }
        val enterChildAddressButton = findViewById<Button>(R.id.enterChildAddressButton)
        enterChildAddressButton.setOnClickListener { _: View? -> loadDiscoveryViaAddress() }
    }

    private fun loadDiscoveryViaMdns() {
        setContentView(R.layout.activity_discover_mdns)
        ThemeManager.applyTheme(this)
        startServiceDiscovery("_childmonitor._tcp.")
    }

    private fun loadDiscoveryViaAddress() {
        setContentView(R.layout.activity_discover_address)
        ThemeManager.applyTheme(this)
        val connectButton = findViewById<Button>(R.id.connectViaAddressButton)
        val addressField = findViewById<EditText>(R.id.ipAddressField)
        val portField = findViewById<EditText>(R.id.portField)
        val preferredAddress = getPreferences(MODE_PRIVATE).getString(PREF_KEY_CHILD_DEVICE_ADDRESS, null)
        if (!preferredAddress.isNullOrEmpty()) {
            addressField.setText(preferredAddress)
        }
        val preferredPort = getPreferences(MODE_PRIVATE).getInt(PREF_KEY_CHILD_DEVICE_PORT, -1)
        if (preferredPort > 0) {
            portField.setText(getString(R.string.portNumber, preferredPort))
        } else {
            portField.setText(R.string.defaultMonitorPort)
        }
        connectButton.setOnClickListener { _: View? ->
            Log.i(TAG, "Connecting to child device via address")
            val addressString = addressField.text.toString()
            val portString = portField.text.toString()
            if (addressString.isEmpty()) {
                AppToast.show(this@DiscoverActivity, R.string.invalidAddress, Toast.LENGTH_LONG)
                return@setOnClickListener
            }
            val port: Int = try {
                portString.toInt()
            } catch (e: NumberFormatException) {
                AppToast.show(this@DiscoverActivity, R.string.invalidPort, Toast.LENGTH_LONG)
                return@setOnClickListener
            }
            val preferencesEditor = getPreferences(MODE_PRIVATE).edit()
            preferencesEditor.putString(PREF_KEY_CHILD_DEVICE_ADDRESS, addressString)
            preferencesEditor.putInt(PREF_KEY_CHILD_DEVICE_PORT, port)
            preferencesEditor.apply()
            connectToChild(addressString, port, addressString)
        }
    }

    /** Set in startServiceDiscovery; invoked on terminal callbacks AND in onDestroy
     *  so the WiFi multicast lock cannot outlive the activity if the user navigates
     *  away mid-discovery (#194-10). */
    private var discoveryMulticastReleaser: (() -> Unit)? = null

    override fun onDestroy() {
        Log.i(TAG, "ChildMonitoring stop")
        this.discoveryListener?.let {
            this.discoveryListener = null
            Log.i(TAG, "Unregistering monitoring service")
            this.nsdManager.stopServiceDiscovery(it)
        }
        // #194-10: belt to the multicastReleased AtomicBoolean — even if all the
        // NSD terminal callbacks fired correctly already, calling release again on
        // a non-counted lock is a no-op (guarded by the AtomicBoolean inside).
        discoveryMulticastReleaser?.invoke()
        discoveryMulticastReleaser = null
        super.onDestroy()
    }

    private fun startServiceDiscovery(serviceType: String) {
        val nsdManager = this.getSystemService(NSD_SERVICE) as NsdManager
        val wifi = this.applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val multicastLock = wifi.createMulticastLock("multicastLock")
        // #194-10 BUG: setReferenceCounted(true) + single acquire() means a second
        // release throws IllegalStateException. Either acquire counts must balance
        // release counts, or we have to disable reference counting. We chose
        // non-counted: one acquire + one release in the terminal callbacks, no
        // intermediate releases in onServiceLost (which used to leak the lock for
        // the rest of the discovery session if it ever recovered).
        multicastLock.setReferenceCounted(false)
        multicastLock.acquire()
        val multicastReleased = java.util.concurrent.atomic.AtomicBoolean(false)
        val multicastReleaser = {
            if (multicastReleased.compareAndSet(false, true)) {
                try {
                    multicastLock.release()
                } catch (ignored: Exception) {
                    // already released or never held
                }
            }
        }
        // Track for onDestroy cleanup so the lock can't outlive the activity.
        this.discoveryMulticastReleaser = multicastReleaser
        val serviceTable = findViewById<ListView>(R.id.ServiceTable)
        val availableServicesAdapter = ArrayAdapter<ServiceInfoWrapper>(this,
                R.layout.available_children_list)
        serviceTable.adapter = availableServicesAdapter
        serviceTable.onItemClickListener = OnItemClickListener { parent: AdapterView<*>, _: View?, position: Int, _: Long ->
            val info = parent.getItemAtPosition(position) as ServiceInfoWrapper
            info.address?.let {
                connectToChild(it, info.port, info.name, info.deviceId)
            }
        }

        // Instantiate a new DiscoveryListener
        this.discoveryListener = object : DiscoveryListener {
            //  Called as soon as service discovery begins.
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // A service was found!  Do something with it.
                Log.d(TAG, "Service discovery success: $service")
                if (service.serviceType != serviceType) {
                    // Service type is the string containing the protocol and
                    // transport layer for this service.
                    Log.d(TAG, "Unknown Service Type: " + service.serviceType)
                } else if (service.serviceName.contains("ChildMonitor")) {
                    try {
                        NsdResolver.resolve(
                            manager = this@DiscoverActivity.nsdManager,
                            service = service,
                            executor = ContextCompat.getMainExecutor(this@DiscoverActivity),
                            onResolved = { serviceInfo ->
                            Log.i(TAG, "Resolve Succeeded: $serviceInfo")
                            runOnUiThread {
                                for (index in 0 until availableServicesAdapter.count) {
                                    val item = availableServicesAdapter.getItem(index)
                                    if (item != null && item.matches(serviceInfo)) {
                                        // Prevent inserting duplicates
                                        return@runOnUiThread
                                    }
                                }
                                availableServicesAdapter.add(ServiceInfoWrapper(serviceInfo))
                                // #364: discovery is otherwise silent for a TalkBack user — announce
                                // each found child (the mdns list has no status view to live-region).
                                val count = availableServicesAdapter.count
                                window.decorView.announceForAccessibility(
                                    resources.getQuantityString(R.plurals.discoveredChildren, count, count)
                                )
                            }
                            },
                            onFailure = { errorCode ->
                                Log.e(TAG, "Resolve failed: error $errorCode for service: $service")
                            },
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Resolve failed synchronously for service: $service", e)
                    }
                } else {
                    Log.d(TAG, "Unknown Service name: " + service.serviceName)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // When the network service is no longer available.
                // Internal bookkeeping code goes here.
                // #194-10: do NOT release the multicast lock here — onServiceLost
                // can fire mid-discovery (another child temporarily off-network)
                // while we still want to find replacements. The lock is released
                // when discovery actually stops, in onDiscoveryStopped /
                // onStartDiscoveryFailed / onStopDiscoveryFailed / onDestroy.
                Log.e(TAG, "Service lost: $service")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
                multicastReleaser()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code: $errorCode")
                nsdManager.stopServiceDiscovery(this)
                multicastReleaser()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code: $errorCode")
                nsdManager.stopServiceDiscovery(this)
                multicastReleaser()
            }
        }
        nsdManager.discoverServices(
                serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener
        )
    }

    private fun connectToChild(address: String, port: Int, name: String, deviceId: String? = null) {
        val i = Intent(applicationContext, ListenActivity::class.java)
        val b = Bundle()
        b.putString("address", address)
        b.putInt("port", port)
        b.putString("name", name)
        // #278/#402: pass the child's stable deviceId so ListenService can look up the bearer
        // token and send a valid CMHELLO (matches KidConnect/ListenActivity extras contract).
        // Only set it when non-blank — the legacy enter-address path has no id and must keep
        // working (ListenActivity then recovers the id from the paired store, or connects
        // tokenless for a never-paired child).
        // Only enable auto-reconnect (which includes the NSD discovery fallback) when we actually
        // have the child's deviceId. Discovery with a null target would adopt ANY child advertising
        // on the LAN (KidReconnectPolicy.acceptsDiscovered(null, …) accepts the first one), so the
        // manual enter-address path (no deviceId) keeps its old behaviour: connect once, no fallback.
        deviceId?.takeIf { it.isNotBlank() }?.let {
            b.putString("deviceId", it)
            b.putBoolean("autoReconnect", true)
        }
        i.putExtras(b)
        startActivity(i)
    }

    companion object {
        private const val TAG = "ChildMonitor"
        private const val PREF_KEY_CHILD_DEVICE_ADDRESS = "childDeviceAddress"
        private const val PREF_KEY_CHILD_DEVICE_PORT = "childDevicePort"
    }
}

internal class ServiceInfoWrapper(private val info: NsdServiceInfo) {
    fun matches(other: NsdServiceInfo): Boolean {
        return NsdResolver.addresses(info).toSet() == NsdResolver.addresses(other).toSet() &&
            info.port == other.port
    }

    val address: String?
        get() = NsdResolver.addresses(info).firstOrNull()?.hostAddress
    val port: Int
        get() = info.port
    // If there is more than one service with the same name on the network, it will
    // have a number at the end, but will appear as the following:
    //   "ChildMonitor\\032(number)
    // or
    //   "ChildMonitor\032(number)
    // Replace \\032 and \032 with a " "
    val name: String = info.serviceName
        .replace("\\\\032", " ")
        .replace("\\032", " ")

    // #278/#402: the stable child device id, required so ListenService can look up the
    // bearer token and send a valid CMHELLO (without it the child rejects with
    // reason=format/401 and the parent stays "disconnected"). Mirrors
    // NsdDiscoveryHelper.onServiceResolved: prefer the explicit TXT 'id' attribute,
    // fall back to parsing a "ChildMonitor on FOO [abc12345]" suffix for older children.
    val deviceId: String? = run {
        val txtId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                info.attributes?.get("id")?.toString(Charsets.UTF_8)
            }.getOrNull()
        } else null
        val suffixId = Regex("\\[([0-9a-fA-F]{4,})]").find(name)?.groupValues?.getOrNull(1)
        txtId?.takeIf { it.isNotBlank() } ?: suffixId
    }

    override fun toString(): String {
        return name
    }
}
