package de.rochefort.childmonitor.integrations.mqtt

import java.net.URI

/** Persisted, non-secret configuration for the opt-in local MQTT bridge (#305/#315). */
data class MqttConfig(
    val enabled: Boolean = false,
    val serverUri: String = "",
    val username: String = "",
    val baseTopic: String,
    val temperatureTopic: String = "",
    val humidityTopic: String = "",
    val temperatureMinC: Double = 16.0,
    val temperatureMaxC: Double = 24.0,
    val humidityMinPct: Double = 30.0,
    val humidityMaxPct: Double = 70.0,
)

object MqttConfigValidation {
    private val topicPart = Regex("[A-Za-z0-9._-]+")

    /** Returns a stable error code, or null when the configuration is safe to activate. */
    fun error(config: MqttConfig, passwordSet: Boolean): String? {
        if (config.serverUri.length > 300 || config.username.length > 128) return "value_too_long"
        if (!validTopic(config.baseTopic)) return "invalid_base_topic"
        if (config.temperatureTopic.isNotBlank() && !validTopic(config.temperatureTopic)) {
            return "invalid_temperature_topic"
        }
        if (config.humidityTopic.isNotBlank() && !validTopic(config.humidityTopic)) {
            return "invalid_humidity_topic"
        }
        if (!config.temperatureMinC.isFinite() || !config.temperatureMaxC.isFinite() ||
            config.temperatureMinC !in -30.0..60.0 || config.temperatureMaxC !in -30.0..60.0 ||
            config.temperatureMinC >= config.temperatureMaxC
        ) return "invalid_temperature_range"
        if (!config.humidityMinPct.isFinite() || !config.humidityMaxPct.isFinite() ||
            config.humidityMinPct !in 0.0..100.0 || config.humidityMaxPct !in 0.0..100.0 ||
            config.humidityMinPct >= config.humidityMaxPct
        ) return "invalid_humidity_range"

        if (config.serverUri.isBlank()) return if (config.enabled) "server_required" else null
        val uri = try { URI(config.serverUri.trim()) } catch (_: Exception) { return "invalid_server" }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "tcp" && scheme != "ssl") return "invalid_server_scheme"
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null ||
            (!uri.path.isNullOrBlank() && uri.path != "/") || uri.port !in -1..65535 || uri.port == 0
        ) return "invalid_server"
        // Never send broker credentials over plaintext, even on a local LAN.
        if ((config.username.isNotBlank() || passwordSet) && scheme != "ssl") {
            return "credentials_require_tls"
        }
        return null
    }

    fun validTopic(value: String): Boolean {
        if (value.isBlank() || value.length > 180 || value.startsWith('/') || value.endsWith('/')) return false
        if ('+' in value || '#' in value || '\u0000' in value) return false
        return value.split('/').all { it.isNotBlank() && topicPart.matches(it) }
    }
}

data class EnvironmentReading(
    val temperatureC: Double? = null,
    val humidityPct: Double? = null,
    val updatedAtEpochMs: Long = 0L,
) {
    enum class Alert { TEMPERATURE_LOW, TEMPERATURE_HIGH, HUMIDITY_LOW, HUMIDITY_HIGH }

    fun alert(config: MqttConfig): Alert? = when {
        temperatureC != null && temperatureC < config.temperatureMinC -> Alert.TEMPERATURE_LOW
        temperatureC != null && temperatureC > config.temperatureMaxC -> Alert.TEMPERATURE_HIGH
        humidityPct != null && humidityPct < config.humidityMinPct -> Alert.HUMIDITY_LOW
        humidityPct != null && humidityPct > config.humidityMaxPct -> Alert.HUMIDITY_HIGH
        else -> null
    }

    companion object {
        fun parseTemperature(payload: String): Double? =
            payload.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in -30.0..60.0 }

        fun parseHumidity(payload: String): Double? =
            payload.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
    }
}
