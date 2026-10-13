package de.rochefort.childmonitor.integrations.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttConfigTest {
    private fun valid() = MqttConfig(
        enabled = true,
        serverUri = "ssl://mqtt.home.arpa:8883",
        username = "monitor",
        baseTopic = "child-monitor/nursery",
    )

    @Test fun `valid TLS configuration accepts credentials`() {
        assertNull(MqttConfigValidation.error(valid(), passwordSet = true))
    }

    @Test fun `credentials are never allowed over plaintext MQTT`() {
        assertEquals(
            "credentials_require_tls",
            MqttConfigValidation.error(valid().copy(serverUri = "tcp://192.168.1.5:1883"), true),
        )
    }

    @Test fun `anonymous local TCP is allowed`() {
        assertNull(MqttConfigValidation.error(
            valid().copy(serverUri = "tcp://192.168.1.5:1883", username = ""), false,
        ))
    }

    @Test fun `unsafe broker URIs and wildcard state topics are rejected`() {
        assertEquals("invalid_server", MqttConfigValidation.error(valid().copy(serverUri = "ssl://user@host:8883/path"), true))
        assertFalse(MqttConfigValidation.validTopic("child-monitor/+/motion"))
        assertFalse(MqttConfigValidation.validTopic("/child-monitor"))
        assertTrue(MqttConfigValidation.validTopic("child-monitor/nursery_1"))
    }

    @Test fun `environment values and thresholds are finite and bounded`() {
        assertEquals(21.5, EnvironmentReading.parseTemperature(" 21.5 ")!!, 0.0)
        assertNull(EnvironmentReading.parseTemperature("NaN"))
        assertNull(EnvironmentReading.parseHumidity("101"))
        assertEquals(
            EnvironmentReading.Alert.TEMPERATURE_HIGH,
            EnvironmentReading(temperatureC = 25.0).alert(valid()),
        )
    }

    @Test fun `Home Assistant discovery uses stable entities and no broker secret`() {
        val messages = HomeAssistantDiscovery.messages(
            valid().copy(temperatureTopic = "nursery/temp", humidityTopic = "nursery/humidity"),
            "AbC-123",
            "Nursery",
        )
        assertEquals(5, messages.size)
        assertTrue(messages.any { it.topic == "homeassistant/binary_sensor/child_monitor_abc-123_motion/config" })
        assertTrue(messages.all { "\"username\"" !in it.payload })
        assertTrue(messages.all { "mqtt.home.arpa" !in it.payload })
    }

    @Test fun `MQTT client id is stable bounded and contains no device identifier`() {
        val id = MqttIntegration.stableClientId("private-device-identifier")
        assertEquals(id, MqttIntegration.stableClientId("private-device-identifier"))
        assertTrue(id.length <= 23)
        assertFalse("private" in id)
    }
}
