package de.rochefort.childmonitor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class PairingActivity : Activity() {

    private lateinit var challengeStatus: TextView
    private lateinit var challengePin: TextView
    private lateinit var challengeExpires: TextView
    private lateinit var challengePayload: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var noDevicesText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)
        ThemeManager.applyTheme(this)

        challengeStatus = findViewById(R.id.pairingChallengeStatus)
        challengePin = findViewById(R.id.pairingPinValue)
        challengeExpires = findViewById(R.id.pairingExpiryValue)
        challengePayload = findViewById(R.id.pairingPayloadValue)
        devicesContainer = findViewById(R.id.pairingDevicesContainer)
        noDevicesText = findViewById(R.id.pairingNoDevicesText)

        findViewById<Button>(R.id.pairingGenerateButton).setOnClickListener { generateChallenge() }
        findViewById<Button>(R.id.pairingRefreshButton).setOnClickListener { refreshDevices() }
        findViewById<Button>(R.id.pairingOpenChildButton).setOnClickListener {
            startActivity(Intent(this, MonitorActivity::class.java))
        }

        // #194-6: BASE_URL targets 127.0.0.1:11000, which is the local
        // WebControlServer that MonitorService stands up on the CHILD device.
        // If a parent-only device opens this screen, every request will fail
        // silently via the generic catch in httpGetJson. Detect that case
        // upfront and tell the user how to fix it instead of leaving them
        // staring at a non-functional UI.
        if (!isChildServiceRunningLocally()) {
            challengeStatus.setText(R.string.pairingStartChildRequired)
            challengePin.setText(R.string.valueUnavailable)
            challengeExpires.setText(R.string.valueUnavailable)
            challengePayload.setText(R.string.pairingLocalServiceExplanation)
        }
    }

    /**
     * True iff [MonitorService] is currently running in this process — the only
     * source of the localhost web console PairingActivity talks to. Used to gate
     * the UI on parent-only devices that don't have it (#194-6).
     */
    @Suppress("DEPRECATION")
    private fun isChildServiceRunningLocally(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == MonitorService::class.java.name
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.applyTheme(this)
        refreshDevices()
    }

    private fun generateChallenge() {
        challengeStatus.setText(R.string.pairingRequestingChallenge)
        Thread {
            val response = httpGetJson("$BASE_URL/api/pairing/challenge")
            runOnUiThread {
                if (response == null) {
                    showServiceUnavailable()
                    return@runOnUiThread
                }
                val pin = response.optString("pin", getString(R.string.valueNotAvailable))
                val expiresAtMs = response.optLong("expiresAtMs", -1L)
                val payload = response.optString("qrPayload", "")

                challengeStatus.setText(R.string.pairingChallengeActive)
                challengePin.text = pin
                challengeExpires.text = if (expiresAtMs > 0) {
                    android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", expiresAtMs)
                } else {
                    getString(R.string.valueNotAvailable)
                }
                challengePayload.text = payload.ifBlank { getString(R.string.pairingNoPayload) }
            }
        }.start()
    }

    private fun refreshDevices() {
        Thread {
            val response = httpGetJson("$BASE_URL/api/pairing/devices")
            runOnUiThread {
                if (response == null) {
                    renderNoDevices(getString(R.string.pairingApiUnavailableStartChild))
                    return@runOnUiThread
                }
                val devices = response.optJSONArray("devices")
                if (devices == null || devices.length() == 0) {
                    renderNoDevices(getString(R.string.pairingNoPairedDevices))
                    return@runOnUiThread
                }

                noDevicesText.visibility = View.GONE
                devicesContainer.removeAllViews()
                for (i in 0 until devices.length()) {
                    val item = devices.optJSONObject(i) ?: continue
                    val deviceId = item.optString("deviceId")
                    val deviceName = item.optString("deviceName", getString(R.string.parentDevice))
                    val pairedAt = item.optLong("pairedAtMs", -1L)

                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(24, 24, 24, 24)
                        setBackgroundResource(R.drawable.bg_surface_soft)
                        tag = "theme_soft"
                    }

                    val title = TextView(this).apply {
                        text = deviceName
                        textSize = 16f
                        setTextColor(ContextCompat.getColor(this@PairingActivity, R.color.text_primary))
                    }
                    val details = TextView(this).apply {
                        val pairedText = if (pairedAt > 0L) {
                            android.text.format.DateFormat.getMediumDateFormat(this@PairingActivity)
                                .format(java.util.Date(pairedAt)) + " " +
                                android.text.format.DateFormat.getTimeFormat(this@PairingActivity)
                                    .format(java.util.Date(pairedAt))
                        } else getString(R.string.childControlsUnknown)
                        text = getString(R.string.pairingDeviceDetails, deviceId, pairedText)
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(this@PairingActivity, R.color.text_secondary))
                    }
                    val revokeButton = Button(this).apply {
                        setText(R.string.pairingRevoke)
                        isAllCaps = false
                        setBackgroundResource(R.drawable.bg_button_secondary)
                        tag = "theme_btn_secondary"
                        setOnClickListener { revokeDevice(deviceId) }
                    }

                    card.addView(title)
                    card.addView(details)
                    card.addView(revokeButton)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 16
                    }
                    devicesContainer.addView(card, params)
                }
                ThemeManager.applyTheme(this)
            }
        }.start()
    }

    private fun revokeDevice(deviceId: String) {
        if (deviceId.isBlank()) return
        Thread {
            val encoded = URLEncoder.encode(deviceId, "UTF-8")
            val result = httpGetJson("$BASE_URL/api/pairing/revoke?deviceId=$encoded")
            runOnUiThread {
                val success = result?.optBoolean("success", false) == true
                // #364: toasts aren't reliably spoken by TalkBack — announce the outcome too.
                val msg = getString(if (success) R.string.pairingDeviceRevoked else R.string.pairingRevokeFailed)
                AppToast.show(this, msg, Toast.LENGTH_SHORT)
                window.decorView.announceForAccessibility(msg)
                if (success) refreshDevices()
            }
        }.start()
    }

    private fun renderNoDevices(message: String) {
        devicesContainer.removeAllViews()
        noDevicesText.visibility = View.VISIBLE
        noDevicesText.text = message
    }

    private fun showServiceUnavailable() {
        challengeStatus.setText(R.string.pairingApiUnavailable)
        challengePin.setText(R.string.valueUnavailable)
        challengeExpires.setText(R.string.valueUnavailable)
        challengePayload.setText(R.string.pairingActivateEndpoints)
    }

    private fun httpGetJson(urlString: String): JSONObject? {
        return try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty(
                de.rochefort.childmonitor.security.LocalControlAuthority.HEADER,
                de.rochefort.childmonitor.security.LocalControlAuthority.token,
            )
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.connect()
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: return null
            connection.disconnect()
            JSONObject(response)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val BASE_URL = "http://127.0.0.1:11000"
    }
}
