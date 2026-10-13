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

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * Parent-side QR scanner. Decodes a `childmonitor://discover?...` payload, saves the
 * paired child, and launches ListenActivity with the resolved address.
 *
 * The pairing is saved by deviceId in PairedChildrenStore — if the child's IP later
 * changes, the reconnect loop will re-discover by deviceId via NSD, no rescan needed.
 */
class ParentScanActivity : Activity() {

    private lateinit var barcodeView: DecoratedBarcodeView
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_scan)
        ThemeManager.applyTheme(this)

        barcodeView = findViewById(R.id.parentScannerView)
        barcodeView.setStatusText("Point at the child device's QR code")

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA
            )
        }
    }

    override fun onResume() {
        super.onResume()
        handled = false
        if (hasCameraPermission()) {
            barcodeView.resume()
            barcodeView.decodeContinuous(scanCallback)
        }
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    private val scanCallback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult) {
            if (handled) return
            val raw = result.text ?: return
            val payload = PairingPayload.parse(raw)
            if (payload == null) {
                // Not a Child Monitor QR — keep scanning silently.
                Log.d(TAG, "Ignoring non-pairing QR: $raw")
                return
            }
            // Current QRs promise one-scan durable pairing. Never accept a partially prepared v2
            // payload: it cannot bootstrap a refresh credential or fail closed on TLS (#461).
            if (payload.version >= PairingPayload.CURRENT_VERSION && !payload.isDurableSecure()) {
                handled = true
                barcodeView.pause()
                AppToast.show(
                    this@ParentScanActivity,
                    "The child is still preparing secure pairing. Keep its QR screen open and try again.",
                    Toast.LENGTH_LONG,
                )
                barcodeView.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        handled = false
                        barcodeView.resume()
                    }
                }, 2_000L)
                return
            }
            handled = true
            barcodeView.pause()
            onPaired(payload)
        }

        override fun possibleResultPoints(resultPoints: MutableList<com.google.zxing.ResultPoint>?) {}
    }

    private fun onPaired(payload: PairingPayload) {
        val now = System.currentTimeMillis()
        // #402 Kids: auto-name the scanned child so it is saved as a PERMANENT link
        // (isPermanent == userName set). Without a name the kid is non-permanent and can be
        // evicted by the recency cap, forcing a rescan after an IP change. assign() preserves
        // an existing manual rename when re-scanning the same deviceId.
        val autoName = KidAutoName.assign(payload.name, payload.deviceId, PairedChildrenStore.list(this))
        // #465: a secure QR is not accepted unless its bearer can be protected by AndroidKeyStore.
        // Do this before saving roster metadata, otherwise the UI could claim a usable pairing that
        // has no safely persisted credential.
        if (payload.token != null && !ParentSessionStore.put(this, payload.deviceId, payload.token)) {
            AppToast.show(this, R.string.credential_secure_storage_failed, Toast.LENGTH_LONG)
            handled = false
            barcodeView.setStatusText(getString(R.string.scan_secure_storage_retry))
            barcodeView.resume()
            return
        }
        PairedChildrenStore.upsert(
            this,
            PairedChild(
                deviceId = payload.deviceId,
                name = payload.name,
                lastKnownIp = payload.host,
                lastKnownPort = payload.port,
                lastSeenMs = now,
                userName = autoName
            )
        )
        // #193: persist the QR-embedded bearer token so subsequent HTTP / WS calls
        // can authenticate. Legacy QRs (v=1) omit the token — those parents will
        // see 401s on snapshot/camera-toggle/talkback until the child generates
        // a new QR with a token.
        // The bearer was encrypted and committed before the roster entry above (#465).
        // #129: persist the TLS pin so the parent's HTTPS code refuses any later
        // handshake whose leaf cert doesn't match the QR-advertised fingerprint.
        // Re-scanning a QR (e.g. after the child wiped its keystore) overwrites
        // the pin; that's how the user explicitly authorises a cert rotation.
        ParentSessionStore.putTlsPin(
            this,
            payload.deviceId,
            payload.fingerprint,
            payload.httpsPort,
            payload.audioTlsPort
        )
        // Pair once: do not claim success or leave the scanner until the permanent refresh
        // credential has actually been stored. Retry transient listener/network startup races for
        // up to a minute while the child QR remains open (#461).
        if (de.rochefort.childmonitor.security.DurablePairingPolicy.shouldBootstrapRefresh(
                payload.token, payload.fingerprint, payload.httpsPort)) {
            barcodeView.setStatusText("Securing permanent pairing…")
            Thread {
                var durable = false
                repeat(30) { attempt ->
                    if (durable || isFinishing || isDestroyed) return@repeat
                    durable = de.rochefort.childmonitor.parent.SessionRenewer.bootstrapIfNeeded(
                        applicationContext, payload.deviceId, payload.host,
                        System.currentTimeMillis(), force = true,
                    )
                    if (!durable) try { Thread.sleep(2_000L) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt(); return@Thread
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (durable) completePairingUi(payload, autoName, permanent = true)
                    else {
                        AppToast.show(
                            this,
                            "Could not secure permanent pairing. Check both phones are on the same network and scan again.",
                            Toast.LENGTH_LONG,
                        )
                        handled = false
                        barcodeView.setStatusText("Point at the child device's secure QR code")
                        barcodeView.resume()
                    }
                }
            }.apply {
                name = "pairing-refresh-bootstrap"
                isDaemon = true
            }.start()
            return
        }

        // Legacy v1 pairings cannot provide the durable guarantee; retain compatibility but make
        // the limitation explicit instead of silently promising one-scan recovery.
        AppToast.show(this, "Legacy pairing saved; update the child app for permanent pairing.", Toast.LENGTH_LONG)
        completePairingUi(payload, autoName, permanent = false)
    }

    private fun completePairingUi(payload: PairingPayload, autoName: String, permanent: Boolean) {
        if (permanent) {
            AppToast.show(this, "Paired permanently with ${payload.name}", Toast.LENGTH_SHORT)
        }

        // Hand off to ListenActivity with autoReconnect so it falls back to NSD discovery
        // by deviceId if the saved IP is no longer valid.
        val i = Intent(this, ListenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("address", payload.host)
            putExtra("port", payload.port)
            putExtra("name", payload.name)
            putExtra("deviceId", payload.deviceId)
            putExtra(ListenActivity.EXTRA_KID_DISPLAY_NAME, autoName)
            putExtra("autoReconnect", true)
        }
        startActivity(i)
        finish()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                barcodeView.resume()
                barcodeView.decodeContinuous(scanCallback)
            } else {
                AppToast.show(this, "Camera permission required to scan", Toast.LENGTH_LONG)
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "ParentScanActivity"
        private const val REQUEST_CAMERA = 4002
    }
}
