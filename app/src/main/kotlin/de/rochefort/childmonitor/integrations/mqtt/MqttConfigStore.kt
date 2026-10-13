package de.rochefort.childmonitor.integrations.mqtt

import android.content.Context
import de.rochefort.childmonitor.security.ParentCredentialCrypto
import de.rochefort.childmonitor.security.ParentCredentialKind

/** Stores MQTT settings; the optional broker password is authenticated/encrypted in Keystore. */
object MqttConfigStore {
    sealed interface PasswordUpdate {
        data object Keep : PasswordUpdate
        data object Clear : PasswordUpdate
        data class Set(val value: String) : PasswordUpdate
    }

    data class Stored(
        val config: MqttConfig,
        val password: String?,
        val passwordSet: Boolean,
        val credentialError: Boolean = false,
    )

    fun load(context: Context, deviceId: String): Stored {
        val p = prefs(context)
        val config = MqttConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            serverUri = p.getString(KEY_SERVER, "").orEmpty(),
            username = p.getString(KEY_USERNAME, "").orEmpty(),
            baseTopic = p.getString(KEY_BASE_TOPIC, null) ?: "child-monitor/$deviceId",
            temperatureTopic = p.getString(KEY_TEMPERATURE_TOPIC, "").orEmpty(),
            humidityTopic = p.getString(KEY_HUMIDITY_TOPIC, "").orEmpty(),
            temperatureMinC = java.lang.Double.longBitsToDouble(p.getLong(KEY_TEMPERATURE_MIN, 16.0.toBits())),
            temperatureMaxC = java.lang.Double.longBitsToDouble(p.getLong(KEY_TEMPERATURE_MAX, 24.0.toBits())),
            humidityMinPct = java.lang.Double.longBitsToDouble(p.getLong(KEY_HUMIDITY_MIN, 30.0.toBits())),
            humidityMaxPct = java.lang.Double.longBitsToDouble(p.getLong(KEY_HUMIDITY_MAX, 70.0.toBits())),
        )
        val encrypted = p.getString(KEY_PASSWORD, null)
        if (encrypted.isNullOrBlank()) return Stored(config, null, false)
        return try {
            Stored(
                config,
                ParentCredentialCrypto.decrypt(deviceId, ParentCredentialKind.MQTT_PASSWORD, encrypted),
                true,
            )
        } catch (_: Exception) {
            // Fail closed: a broken password envelope must never silently attempt a weaker or
            // anonymous connection that may publish to the wrong broker identity.
            Stored(config.copy(enabled = false), null, true, credentialError = true)
        }
    }

    /** Encrypt first, then commit password + non-secret fields atomically. */
    fun save(
        context: Context,
        deviceId: String,
        config: MqttConfig,
        passwordUpdate: PasswordUpdate,
    ): Boolean {
        val p = prefs(context)
        val encrypted = when (passwordUpdate) {
            PasswordUpdate.Keep -> p.getString(KEY_PASSWORD, null)
            PasswordUpdate.Clear -> null
            is PasswordUpdate.Set -> {
                if (passwordUpdate.value.isBlank()) return false
                try {
                    ParentCredentialCrypto.encrypt(
                        context, deviceId, ParentCredentialKind.MQTT_PASSWORD, passwordUpdate.value,
                    )
                } catch (_: Exception) {
                    return false
                }
            }
        }
        return p.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_SERVER, config.serverUri.trim())
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_BASE_TOPIC, config.baseTopic.trim())
            .putString(KEY_TEMPERATURE_TOPIC, config.temperatureTopic.trim())
            .putString(KEY_HUMIDITY_TOPIC, config.humidityTopic.trim())
            .putLong(KEY_TEMPERATURE_MIN, config.temperatureMinC.toBits())
            .putLong(KEY_TEMPERATURE_MAX, config.temperatureMaxC.toBits())
            .putLong(KEY_HUMIDITY_MIN, config.humidityMinPct.toBits())
            .putLong(KEY_HUMIDITY_MAX, config.humidityMaxPct.toBits())
            .apply { if (encrypted == null) remove(KEY_PASSWORD) else putString(KEY_PASSWORD, encrypted) }
            .commit()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "MqttIntegration"
    private const val KEY_ENABLED = "enabled_v1"
    private const val KEY_SERVER = "server_v1"
    private const val KEY_USERNAME = "username_v1"
    private const val KEY_PASSWORD = "password_enc_v1"
    private const val KEY_BASE_TOPIC = "base_topic_v1"
    private const val KEY_TEMPERATURE_TOPIC = "temperature_topic_v1"
    private const val KEY_HUMIDITY_TOPIC = "humidity_topic_v1"
    private const val KEY_TEMPERATURE_MIN = "temperature_min_v1"
    private const val KEY_TEMPERATURE_MAX = "temperature_max_v1"
    private const val KEY_HUMIDITY_MIN = "humidity_min_v1"
    private const val KEY_HUMIDITY_MAX = "humidity_max_v1"
}
