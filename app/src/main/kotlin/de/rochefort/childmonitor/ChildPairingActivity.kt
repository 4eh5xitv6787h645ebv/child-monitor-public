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
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.EncodeHintType
import android.os.Handler
import android.os.Looper
import java.net.NetworkInterface
import java.util.Collections

/**
 * Shown on the child device. Renders a QR code containing the child's deviceId + current
 * IP + listening port. Parent scans this to pair without typing an IP.
 *
 * When the child's IP later changes (new WiFi, hotspot, etc.) the parent's saved record
 * keeps the deviceId, so it can re-discover via NSD without rescanning.
 */
class ChildPairingActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_pairing)
        ThemeManager.applyTheme(this)

        val deviceId = ChildIdentity.getId(this)
        val name = ChildIdentity.getName(this)
        val port = MONITOR_PORT

        findViewById<TextView>(R.id.pairingNameLine).text = getString(R.string.childPairingDevice, name)
        findViewById<TextView>(R.id.pairingIdLine).text = getString(R.string.childPairingId, deviceId)
        findViewById<TextView>(R.id.pairingIpLine).setText(R.string.childPairingPreparing)
        findViewById<ImageView>(R.id.pairingQrImage).setImageDrawable(null)

        // Never publish a degraded QR. A v2 scan is accepted only when its bearer, pin and both
        // live TLS listeners exist, guaranteeing the parent can mint its durable credential (#461).
        scheduleSecureRender(deviceId, name, port)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun scheduleSecureRender(deviceId: String, name: String, port: Int) {
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val host = pickPrimaryLocalIp()
            if (host != null && renderSecureQr(deviceId, name, host, port)) return@postDelayed
            findViewById<TextView>(R.id.pairingIpLine).setText(R.string.childPairingWaiting)
            scheduleSecureRender(deviceId, name, port)
        }, TLS_READY_RETRY_INTERVAL_MS)
    }

    private fun renderSecureQr(deviceId: String, name: String, host: String, port: Int): Boolean {
        // #129: include the TLS fingerprint + HTTPS port so the parent can TOFU-pin
        // and switch to TLS for snapshot + WebSocket traffic.
        // Review-pass-1 #2: use the singleton so we read the same cert the
        // running MonitorService is serving (rather than racing through a
        // second loadOrCreate that could overwrite the keystore).
        // Review-pass-1 #3: gate hp= on the LIVE listener (MonitorService
        // .tlsListenerPort) instead of on cert-on-disk. Otherwise a QR could
        // advertise hp=11443 even when the TLS bind failed at startup, and
        // the parent's first snapshot would be a connect-refused.
        val tls = try {
            de.rochefort.childmonitor.security.TlsIdentity.getInstance(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "TlsIdentity unavailable for QR; falling back to plaintext-only payload", e)
            null
        } ?: return false
        val fingerprint = tls.fingerprintSha256() ?: return false
        val httpsPort = MonitorService.tlsListenerPort ?: return false
        val audioTlsPort = MonitorService.tlsAudioListenerPort ?: return false

        // Mint a distinct strict token for the secure QR instead of changing the
        // meaning of [parentToken]. The first plaintext QR may already be visible
        // while TLS starts; keeping that token legacy avoids a transition race
        // where a scan has no `ap=` endpoint but the child suddenly rejects raw.
        val qrToken = de.rochefort.childmonitor.security.SessionAuthority
            .getInstance(applicationContext)
            .issueToken(deviceId, tlsAudioRequired = true)
            .optString("token")
            .takeIf { it.isNotBlank() } ?: return false

        val payload = PairingPayload(
            deviceId = deviceId,
            name = name,
            host = host,
            port = port,
            token = qrToken,
            fingerprint = fingerprint,
            httpsPort = httpsPort,
            audioTlsPort = audioTlsPort
        )

        val qrView = findViewById<ImageView>(R.id.pairingQrImage)
        try {
            qrView.setImageBitmap(buildQrBitmap(payload.encode(), 640))
            findViewById<TextView>(R.id.pairingIpLine).text =
                getString(R.string.childPairingReady, host, port)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render QR", e)
            findViewById<TextView>(R.id.pairingIpLine).setText(R.string.childPairingQrFailed)
            return false
        }
    }

    /** Pick the first non-loopback IPv4 address on a non-virtual interface. */
    private fun pickPrimaryLocalIp(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            interfaces
                .asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { intf -> Collections.list(intf.inetAddresses).asSequence() }
                .map { it.hostAddress.orEmpty() }
                .firstOrNull { addr -> addr.isNotBlank() && !addr.contains(':') && !addr.startsWith("127.") }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Render a QR code with high error correction so a worn or partially obscured display
     * still scans. 640px is a good balance between density and readability across Android
     * displays.
     */
    private fun buildQrBitmap(content: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    companion object {
        private const val TAG = "ChildPairingActivity"
        private const val MONITOR_PORT = 10000
        private const val TLS_READY_RETRY_INTERVAL_MS = 1000L
    }
}
