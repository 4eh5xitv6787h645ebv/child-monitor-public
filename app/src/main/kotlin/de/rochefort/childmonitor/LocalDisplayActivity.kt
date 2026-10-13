package de.rochefort.childmonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import de.rochefort.childmonitor.cast.CastSessionAuthority
import de.rochefort.childmonitor.cast.DlnaDiscoveryClient
import de.rochefort.childmonitor.cast.DlnaRenderer
import de.rochefort.childmonitor.cast.LocalDisplayOutputManager
import java.util.Date
import java.util.concurrent.Executors

/** Parent-side setup for local browser/tablet and DLNA display output (#307). */
class LocalDisplayActivity : Activity() {
    private lateinit var address: String
    private lateinit var deviceId: String
    private lateinit var status: TextView
    private lateinit var discoverButton: Button
    private lateinit var shareButton: Button
    private lateinit var stopButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var deviceList: LinearLayout
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-display-setup").apply { isDaemon = true }
    }
    private var castToken: String? = null
    private var viewerUrl: String? = null
    private var streamUrl: String? = null
    private var snapshotUrl: String? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        if (!PairingValidation.isSafeHost(address) || deviceId.isBlank()) {
            finish()
            return
        }
        setContentView(R.layout.activity_local_display)
        ThemeManager.applyTheme(this)
        status = findViewById(R.id.localDisplayStatus)
        discoverButton = findViewById(R.id.localDisplayDiscover)
        shareButton = findViewById(R.id.localDisplayShare)
        stopButton = findViewById(R.id.localDisplayStop)
        progress = findViewById(R.id.localDisplayProgress)
        deviceList = findViewById(R.id.localDisplayDevices)

        discoverButton.setOnClickListener { discoverDisplays() }
        shareButton.setOnClickListener { prepareViewerShare() }
        stopButton.setOnClickListener { stopAndRevoke() }
        progress.visibility = View.GONE
        discoverButton.isEnabled = true
        shareButton.isEnabled = true
        val active = LocalDisplayOutputManager.activeRendererName()
        if (active == null) {
            status.setText(R.string.localDisplayChoose)
            stopButton.isEnabled = false
        } else {
            status.text = getString(R.string.localDisplayPlaying, active)
            stopButton.isEnabled = true
        }
    }

    override fun onResume() {
        super.onResume()
        val active = LocalDisplayOutputManager.activeRendererName()
        if (active != null) {
            stopButton.isEnabled = true
            status.text = if (LocalDisplayOutputManager.failed()) {
                getString(R.string.localDisplaySlideshowFailed, active)
            } else {
                getString(R.string.localDisplayPlaying, active)
            }
        }
    }

    private fun requestCastGrant(onReady: (() -> Unit)? = null) {
        setBusy(true)
        worker.execute {
            val result = ParentControlClient(applicationContext, address, deviceId)
                .post("/api/cast/session")
            val token = result?.optString("token")
            val expiresAt = result?.optLong("expiresAtMs", 0L) ?: 0L
            val ok = CastSessionAuthority.isTokenShapeValid(token) && expiresAt > System.currentTimeMillis()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (!ok) {
                    status.setText(R.string.localDisplayUnavailable)
                    return@runOnUiThread
                }
                castToken = token
                val authority = NetworkEndpoint.hostForAuthority(address)
                viewerUrl = "http://$authority:${ParentControlClient.CLEARTEXT_PORT}/cast?cast=$token"
                streamUrl = "http://$authority:${ParentControlClient.CLEARTEXT_PORT}/cast/stream.mjpeg?cast=$token"
                snapshotUrl = "http://$authority:${ParentControlClient.CLEARTEXT_PORT}/cast/snapshot?cast=$token"
                val formattedExpiry = DateFormat.getTimeFormat(this).format(Date(expiresAt))
                status.text = getString(R.string.localDisplayReady, formattedExpiry)
                discoverButton.isEnabled = true
                shareButton.isEnabled = true
                stopButton.isEnabled = true
                onReady?.invoke()
            }
        }
    }

    private fun discoverDisplays() {
        setBusy(true)
        status.setText(R.string.localDisplaySearching)
        deviceList.removeAllViews()
        worker.execute {
            acquireMulticastLock()
            val renderers = try { DlnaDiscoveryClient().discover() } finally { releaseMulticastLock() }
            val namedRenderers = renderers.map {
                if (it.friendlyName.isBlank()) it.copy(friendlyName = getString(R.string.localDisplayUnnamed)) else it
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (namedRenderers.isEmpty()) {
                    status.setText(R.string.localDisplayNoneFound)
                } else {
                    status.text = resources.getQuantityString(
                        R.plurals.localDisplaysFound,
                        namedRenderers.size,
                        namedRenderers.size,
                    )
                    renderRenderers(namedRenderers)
                }
            }
        }
    }

    private fun renderRenderers(renderers: List<DlnaRenderer>) {
        deviceList.removeAllViews()
        val density = resources.displayMetrics.density
        renderers.forEach { renderer ->
            val button = Button(this).apply {
                text = getString(R.string.localDisplayDeviceButton, renderer.friendlyName)
                isAllCaps = false
                minHeight = (48 * density).toInt()
                setOnClickListener { chooseMode(renderer) }
            }
            deviceList.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (8 * density).toInt() },
            )
        }
    }

    private fun chooseMode(renderer: DlnaRenderer) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.localDisplayModeTitle, renderer.friendlyName))
            .setItems(R.array.localDisplayModes) { _, which -> playOnRenderer(renderer, slideshow = which == 1) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun playOnRenderer(renderer: DlnaRenderer, slideshow: Boolean) {
        // Mint a fresh one-renderer grant for every output selection. Replacing the prior grant
        // immediately terminates any existing MJPEG stream and allows first-use IP binding to the
        // newly selected television instead of the previous one.
        requestCastGrant { playOnRendererWithFreshGrant(renderer, slideshow) }
    }

    private fun playOnRendererWithFreshGrant(renderer: DlnaRenderer, slideshow: Boolean) {
        val mediaUrl = if (slideshow) snapshotUrl else streamUrl
        if (mediaUrl == null) return
        val displayTitle = getString(R.string.localDisplayViewerTitle)
        setBusy(true)
        status.text = getString(R.string.localDisplayConnecting, renderer.friendlyName)
        worker.execute {
            val ok = if (slideshow) {
                LocalDisplayOutputManager.playSlideshow(renderer, mediaUrl, displayTitle)
            } else {
                LocalDisplayOutputManager.playLive(renderer, mediaUrl, displayTitle)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                status.text = getString(
                    if (ok) R.string.localDisplayPlaying else R.string.localDisplayFailed,
                    renderer.friendlyName,
                )
            }
        }
    }

    private fun shareViewerLink() {
        val url = viewerUrl ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        startActivity(Intent.createChooser(send, getString(R.string.localDisplayShareChooser)))
    }

    private fun prepareViewerShare() {
        setBusy(true)
        worker.execute {
            // A grant intentionally binds to one display IP. Stop any prior DLNA target before
            // minting the browser grant so the old output cannot consume or outlive the new link.
            LocalDisplayOutputManager.stop()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) requestCastGrant { shareViewerLink() }
            }
        }
    }

    private fun stopAndRevoke() {
        setBusy(true)
        worker.execute {
            LocalDisplayOutputManager.stop()
            ParentControlClient(applicationContext, address, deviceId).post("/api/cast/revoke")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                castToken = null
                viewerUrl = null
                streamUrl = null
                snapshotUrl = null
                setBusy(false)
                discoverButton.isEnabled = true
                shareButton.isEnabled = true
                stopButton.isEnabled = false
                deviceList.removeAllViews()
                status.setText(R.string.localDisplayStopped)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        discoverButton.isEnabled = !busy
        shareButton.isEnabled = !busy
        stopButton.isEnabled = !busy &&
            (castToken != null || LocalDisplayOutputManager.activeRendererName() != null)
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("ChildMonitorDlnaDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            multicastLock = null
        }
    }

    private fun releaseMulticastLock() {
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    override fun onDestroy() {
        releaseMulticastLock()
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_DEVICE_ID = "deviceId"
    }
}
