package de.rochefort.childmonitor.integrations.mqtt

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Opt-in MQTT/Home Assistant bridge. All client lifecycle and publishing is serialized on one
 * daemon executor so audio/camera callbacks can never block on broker I/O.
 */
class MqttIntegration(
    context: Context,
    private val deviceId: String,
    private val displayName: String,
    private val parentConnected: () -> Boolean,
    private val onEnvironmentChanged: (EnvironmentReading, EnvironmentReading.Alert?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cm-mqtt").apply { isDaemon = true }
    }
    @Volatile private var stopped = false
    @Volatile private var connected = false
    @Volatile private var lastError: String? = null
    @Volatile private var passwordSet = false
    @Volatile private var reading = EnvironmentReading()
    @Volatile private var activeConfig: MqttConfig = MqttConfig(baseTopic = "child-monitor/$deviceId")
    private var client: MqttAsyncClient? = null
    private var retryScheduled = false

    fun start() = executor.execute { reloadLocked() }

    /** Reload after an authenticated settings update. */
    fun reload() = executor.execute { reloadLocked() }

    fun publishSound() = pulse("sound")
    fun publishMotion() = pulse("motion")

    fun publishParentConnected(value: Boolean) = executor.execute {
        publishLocked("${activeConfig.baseTopic}/parent_connected", if (value) "ON" else "OFF", retained = true)
    }

    fun stateJson(): JSONObject = JSONObject().apply {
        val cfg = activeConfig
        put("enabled", cfg.enabled)
        put("connected", connected)
        put("serverConfigured", cfg.serverUri.isNotBlank())
        put("server", cfg.serverUri)
        put("username", cfg.username)
        put("baseTopic", cfg.baseTopic)
        put("temperatureTopic", cfg.temperatureTopic)
        put("humidityTopic", cfg.humidityTopic)
        put("temperatureMinC", cfg.temperatureMinC)
        put("temperatureMaxC", cfg.temperatureMaxC)
        put("humidityMinPct", cfg.humidityMinPct)
        put("humidityMaxPct", cfg.humidityMaxPct)
        put("passwordSet", passwordSet)
        put("lastError", lastError ?: JSONObject.NULL)
        put("temperatureC", reading.temperatureC ?: JSONObject.NULL)
        put("humidityPct", reading.humidityPct ?: JSONObject.NULL)
        put("environmentUpdatedAtMs", reading.updatedAtEpochMs.takeIf { it > 0 } ?: JSONObject.NULL)
        put("environmentAlert", reading.alert(cfg)?.name?.lowercase() ?: JSONObject.NULL)
    }

    fun stop() {
        stopped = true
        executor.execute { disconnectLocked(publishOffline = true) }
        executor.shutdown()
        try { executor.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        executor.shutdownNow()
    }

    private fun pulse(kind: String) = executor.execute {
        val topic = "${activeConfig.baseTopic}/$kind"
        if (publishLocked(topic, "ON", retained = false)) {
            executor.schedule({ publishLocked(topic, "OFF", retained = false) }, 2, TimeUnit.SECONDS)
        }
    }

    private fun reloadLocked() {
        if (stopped) return
        val stored = MqttConfigStore.load(appContext, deviceId)
        activeConfig = stored.config
        passwordSet = stored.passwordSet
        reading = EnvironmentReading()
        lastError = if (stored.credentialError) "credential_unavailable" else null
        disconnectLocked(publishOffline = false)
        if (!stored.config.enabled || stored.credentialError) return
        val validation = MqttConfigValidation.error(stored.config, stored.passwordSet)
        if (validation != null) {
            lastError = validation
            return
        }
        connectLocked(stored)
    }

    private fun connectLocked(stored: MqttConfigStore.Stored) {
        if (stopped || !stored.config.enabled || client?.isConnected == true) return
        try {
            // Initial failures are retried explicitly (automatic reconnect only applies after a
            // successful connection). Retire the failed client before replacing it each time.
            disconnectLocked(publishOffline = false)
            val next = MqttAsyncClient(
                stored.config.serverUri.trim(), stableClientId(deviceId), MemoryPersistence(),
            )
            client = next
            next.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    executor.execute {
                        if (stopped || next !== client) return@execute
                        connected = true
                        lastError = null
                        retryScheduled = false
                        publishInitialStateLocked()
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    connected = false
                    lastError = safeError(cause)
                }

                override fun messageArrived(topic: String?, message: org.eclipse.paho.client.mqttv3.MqttMessage?) {
                    val payload = message?.payload?.toString(Charsets.UTF_8) ?: return
                    executor.execute { handleEnvironmentLocked(topic.orEmpty(), payload) }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                maxInflight = 10
                setWill("${stored.config.baseTopic}/availability", "offline".toByteArray(), 1, true)
                stored.config.username.takeIf { it.isNotBlank() }?.let { userName = it }
                stored.password?.let { password = it.toCharArray() }
            }
            next.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit // connectComplete owns state
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    executor.execute {
                        if (next !== client || stopped) return@execute
                        connected = false
                        lastError = safeError(exception)
                        scheduleRetryLocked()
                    }
                }
            })
        } catch (e: Exception) {
            connected = false
            lastError = safeError(e)
            scheduleRetryLocked()
        }
    }

    private fun publishInitialStateLocked() {
        val cfg = activeConfig
        publishLocked("${cfg.baseTopic}/availability", "online", retained = true)
        publishLocked("${cfg.baseTopic}/sound", "OFF", retained = true)
        publishLocked("${cfg.baseTopic}/motion", "OFF", retained = true)
        publishLocked("${cfg.baseTopic}/parent_connected", if (parentConnected()) "ON" else "OFF", retained = true)
        HomeAssistantDiscovery.messages(cfg, deviceId, displayName).forEach {
            publishLocked(it.topic, it.payload, retained = true)
        }
        try {
            if (cfg.temperatureTopic.isNotBlank()) client?.subscribe(cfg.temperatureTopic, 1)
            if (cfg.humidityTopic.isNotBlank()) client?.subscribe(cfg.humidityTopic, 1)
        } catch (e: MqttException) {
            lastError = safeError(e)
        }
    }

    private fun handleEnvironmentLocked(topic: String, payload: String) {
        val cfg = activeConfig
        val now = System.currentTimeMillis()
        val next = when (topic) {
            cfg.temperatureTopic -> EnvironmentReading.parseTemperature(payload)?.let {
                reading.copy(temperatureC = it, updatedAtEpochMs = now)
            }
            cfg.humidityTopic -> EnvironmentReading.parseHumidity(payload)?.let {
                reading.copy(humidityPct = it, updatedAtEpochMs = now)
            }
            else -> null
        } ?: return
        reading = next
        next.temperatureC?.let { publishLocked("${cfg.baseTopic}/temperature", it.toString(), true) }
        next.humidityPct?.let { publishLocked("${cfg.baseTopic}/humidity", it.toString(), true) }
        onEnvironmentChanged(next, next.alert(cfg))
    }

    private fun publishLocked(topic: String, payload: String, retained: Boolean): Boolean {
        val current = client ?: return false
        if (!connected || !current.isConnected) return false
        return try {
            current.publish(topic, payload.toByteArray(Charsets.UTF_8), 1, retained)
            true
        } catch (e: MqttException) {
            lastError = safeError(e)
            false
        }
    }

    private fun scheduleRetryLocked() {
        if (retryScheduled || stopped || !activeConfig.enabled) return
        retryScheduled = true
        executor.schedule({
            retryScheduled = false
            if (!stopped) connectLocked(MqttConfigStore.load(appContext, deviceId))
        }, RETRY_SECONDS, TimeUnit.SECONDS)
    }

    private fun disconnectLocked(publishOffline: Boolean) {
        val old = client
        client = null
        retryScheduled = false
        if (publishOffline && old?.isConnected == true) {
            try { old.publish("${activeConfig.baseTopic}/availability", "offline".toByteArray(), 1, true).waitForCompletion(750) } catch (_: Exception) {}
        }
        try { if (old?.isConnected == true) old.disconnectForcibly(500, 500, false) } catch (_: Exception) {}
        try { old?.close() } catch (_: Exception) {}
        connected = false
    }

    private fun safeError(error: Throwable?): String = when (error) {
        is java.net.UnknownHostException -> "host_unreachable"
        is java.net.ConnectException -> "connection_failed"
        is javax.net.ssl.SSLException -> "tls_failed"
        else -> "mqtt_error"
    }

    companion object {
        private const val RETRY_SECONDS = 30L

        internal fun stableClientId(deviceId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray())
            return "childmon-" + digest.take(7).joinToString("") { "%02x".format(it) }
        }
    }
}
