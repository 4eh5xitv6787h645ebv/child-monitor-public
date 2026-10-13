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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import de.rochefort.childmonitor.MonitorService.MonitorBinder

class MonitorActivity : Activity() {
    private lateinit var addressText: TextView
    private lateinit var webConsoleText: TextView
    private lateinit var connectivityManager: ConnectivityManager
    private val networkAddresses = mutableMapOf<Network, List<String>>()
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetwork(network)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            refreshNetwork(network, capabilities = capabilities)

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            refreshNetwork(network, linkProperties = linkProperties)

        override fun onLost(network: Network) {
            synchronized(networkAddresses) { networkAddresses.remove(network) }
            renderNetworkAddresses()
        }
    }
    private var monitorService: MonitorService? = null
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service. Because we have bound to an explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            val bs = (service as MonitorBinder).service
            bs.monitorActivity = this@MonitorActivity
            monitorService = bs
            updateCameraUi(bs.isCameraEnabled(), bs.isCameraActive())
        }

        override fun onServiceDisconnected(className: ComponentName) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            AppToast.show(this@MonitorActivity, R.string.disconnected,
                    Toast.LENGTH_SHORT)
        }
    }
    private var shouldUnbind = false
    private val webConsolePort = 11000
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "ChildMonitor start")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)
        ThemeManager.applyTheme(this)
        addressText = findViewById(R.id.address)
        webConsoleText = findViewById(R.id.webConsoleUrl)
        val cameraToggle = findViewById<Button>(R.id.cameraToggle)
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        renderNetworkAddresses()
        startNetworkTracking()

        cameraToggle.setOnClickListener {
            monitorService?.let { svc ->
                val enabled = !svc.isCameraEnabled()
                svc.setCameraEnabled(enabled)
                updateCameraUi(enabled, svc.isCameraActive())
            } ?: AppToast.show(this, R.string.serviceNotReady, Toast.LENGTH_SHORT)
        }
        findViewById<Button>(R.id.showPairingQrButton)?.setOnClickListener {
            startActivity(Intent(this, ChildPairingActivity::class.java))
        }
        ensureServiceRunningAndBind()

        // Check battery optimization settings and prompt if needed
        BatteryOptimizationHelper.checkAndPromptIfNeeded(this)
    }

    private fun renderNetworkAddresses() {
        if (!::addressText.isInitialized || !::webConsoleText.isInitialized) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(::renderNetworkAddresses)
            return
        }
        val listenAddresses = synchronized(networkAddresses) {
            networkAddresses.values.flatten().distinct().sorted()
        }
        if (listenAddresses.isNotEmpty()) {
            val sb = StringBuilder()
            for (i in listenAddresses.indices) {
                val listenAddress = listenAddresses[i]
                sb.append(listenAddress)
                if (i != listenAddresses.size - 1) {
                    sb.append("\n\n")
                }
            }
            addressText.text = sb.toString()
            val browserAddresses = listenAddresses.map { it.substringBefore(" ") }.distinct()
            webConsoleText.text = browserAddresses.joinToString("\n") { "http://$it:$webConsolePort" }
        } else {
            addressText.setText(R.string.notConnected)
            webConsoleText.setText(R.string.notConnected)
        }
    }

    private fun startNetworkTracking() {
        if (networkCallbackRegistered) return
        val request = NetworkRequest.Builder()
            // The default request excludes VPNs; the child listens on every usable local route.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to monitor child addresses", e)
        }
    }

    private fun refreshNetwork(
        network: Network,
        capabilities: NetworkCapabilities? = null,
        linkProperties: LinkProperties? = null,
    ) {
        // Callback objects can become stale between notifications and lookups. Missing framework
        // snapshots remove the route instead of crashing child mode (#460).
        val caps = capabilities ?: connectivityManager.getNetworkCapabilities(network)
        val links = linkProperties ?: connectivityManager.getLinkProperties(network)
        if (caps == null || links == null) {
            synchronized(networkAddresses) { networkAddresses.remove(network) }
            renderNetworkAddresses()
            return
        }
        val typeName = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "NETWORK"
        }
        val addresses = links.linkAddresses.mapNotNull { linkAddress ->
            val address = linkAddress.address
            address.hostAddress
                ?.takeUnless { address.isLinkLocalAddress || address.isLoopbackAddress }
                ?.let { "$it ($typeName)" }
        }
        synchronized(networkAddresses) {
            if (addresses.isEmpty()) networkAddresses.remove(network)
            else networkAddresses[network] = addresses
        }
        renderNetworkAddresses()
    }

    private fun stopNetworkTracking() {
        if (!networkCallbackRegistered) return
        networkCallbackRegistered = false
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to stop child address monitoring", e)
        } finally {
            synchronized(networkAddresses) { networkAddresses.clear() }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_DASHBOARD, 0, R.string.dashboardTitle).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_SETUP_WIZARD, 0, R.string.setupWizardTitle).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_PAIRING, 0, R.string.pairingTitle).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_AUDIO_CALIBRATION, 0, R.string.audioCalibrationTitle).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_DIAGNOSTICS, 0, R.string.diagnosticsTitle).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_TIMELINE, 0, R.string.timelineTitle).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_VIEW_LOGS, 0, R.string.openDiagnosticLogs).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_BATTERY_SETTINGS, 0, R.string.openBatterySettings).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_DASHBOARD -> {
                startActivity(Intent(this, DashboardActivity::class.java))
                true
            }
            MENU_SETUP_WIZARD -> {
                startActivity(Intent(this, SetupWizardActivity::class.java))
                true
            }
            MENU_PAIRING -> {
                startActivity(Intent(this, PairingActivity::class.java))
                true
            }
            MENU_AUDIO_CALIBRATION -> {
                startActivity(Intent(this, AudioCalibrationActivity::class.java))
                true
            }
            MENU_DIAGNOSTICS -> {
                startActivity(Intent(this, DiagnosticsActivity::class.java))
                true
            }
            MENU_TIMELINE -> {
                startActivity(Intent(this, TimelineActivity::class.java))
                true
            }
            MENU_VIEW_LOGS -> {
                startActivity(Intent(this, LogViewerActivity::class.java))
                true
            }
            MENU_BATTERY_SETTINGS -> {
                BatteryOptimizationHelper.checkAndPromptIfNeeded(this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    public override fun onDestroy() {
        stopNetworkTracking()
        doUnbindAndStopService(stopService = false)
        super.onDestroy()
    }

    @Deprecated("Android legacy back callback retained for API 21 compatibility")
    override fun onBackPressed() {
        doUnbindAndStopService(stopService = false)
        val intent = Intent(this, StartActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun ensureServiceRunningAndBind() {
        val context: Context = this
        val intent = Intent(context, MonitorService::class.java)
        ContextCompat.startForegroundService(context, intent)
        // Attempts to establish a connection with the service.  We use an
        // explicit class name because we want a specific service
        // implementation that we know will be running in our own process
        // (and thus won't be supporting component replacement by other
        // applications).
        if (bindService(intent, connection, BIND_AUTO_CREATE)) {
            this.shouldUnbind = true
            Log.i(TAG, "Bound monitor service")
        } else {
            Log.e(TAG, "Error: The requested service doesn't " +
                    "exist, or this client isn't allowed access to it.")
        }
    }

    private fun doUnbindAndStopService(stopService: Boolean = true) {
        monitorService?.monitorActivity = null
        if (this.shouldUnbind) {
            this.shouldUnbind = false
            // Release information about the service's state.
            unbindService(connection)
        }
        if (stopService) {
            val context: Context = this
            val intent = Intent(context, MonitorService::class.java)
            context.stopService(intent)
        }
    }

    fun updateCameraUi(enabled: Boolean, active: Boolean) {
        val status = findViewById<TextView>(R.id.cameraStatus)
        val toggle = findViewById<Button>(R.id.cameraToggle)
        status.text = when {
            !enabled -> getString(R.string.cameraStatusOff)
            active -> getString(R.string.cameraStatusOn)
            else -> getString(R.string.cameraStatusStarting)
        }
        toggle.text = if (enabled) getString(R.string.turnCameraOff) else getString(R.string.turnCameraOn)
    }

    companion object {
        const val TAG = "ChildMonitor"
        private const val MENU_VIEW_LOGS = 1
        private const val MENU_BATTERY_SETTINGS = 2
        private const val MENU_DASHBOARD = 3
        private const val MENU_SETUP_WIZARD = 4
        private const val MENU_PAIRING = 5
        private const val MENU_AUDIO_CALIBRATION = 6
        private const val MENU_DIAGNOSTICS = 7
        private const val MENU_TIMELINE = 8
    }
}
