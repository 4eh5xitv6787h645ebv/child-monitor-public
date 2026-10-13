package de.rochefort.childmonitor.integrations.mqtt

import org.json.JSONArray
import org.json.JSONObject

/** Pure Home Assistant MQTT discovery payload generation (#305). */
object HomeAssistantDiscovery {
    data class Message(val topic: String, val payload: String)

    fun messages(config: MqttConfig, nodeId: String, displayName: String): List<Message> {
        val safeNode = nodeId.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        val deviceId = "child_monitor_$safeNode"
        val availability = "${config.baseTopic}/availability"
        val device = JSONObject()
            .put("identifiers", JSONArray().put(deviceId))
            .put("name", displayName)
            .put("manufacturer", "Child Monitor")
            .put("model", "Android child device")

        fun binary(kind: String, name: String, deviceClass: String? = null): Message {
            val body = JSONObject()
                .put("unique_id", "${deviceId}_$kind")
                .put("name", name)
                .put("state_topic", "${config.baseTopic}/$kind")
                .put("availability_topic", availability)
                .put("payload_on", "ON")
                .put("payload_off", "OFF")
                .put("device", device)
            if (deviceClass != null) body.put("device_class", deviceClass)
            return Message("homeassistant/binary_sensor/${deviceId}_$kind/config", body.toString())
        }

        val out = mutableListOf(
            binary("sound", "$displayName sound", "sound"),
            binary("motion", "$displayName motion", "motion"),
            binary("parent_connected", "$displayName parent connected", "connectivity"),
        )
        if (config.temperatureTopic.isNotBlank()) {
            val body = JSONObject()
                .put("unique_id", "${deviceId}_temperature")
                .put("name", "$displayName temperature")
                .put("state_topic", "${config.baseTopic}/temperature")
                .put("availability_topic", availability)
                .put("device_class", "temperature")
                .put("state_class", "measurement")
                .put("unit_of_measurement", "°C")
                .put("device", device)
            out += Message("homeassistant/sensor/${deviceId}_temperature/config", body.toString())
        }
        if (config.humidityTopic.isNotBlank()) {
            val body = JSONObject()
                .put("unique_id", "${deviceId}_humidity")
                .put("name", "$displayName humidity")
                .put("state_topic", "${config.baseTopic}/humidity")
                .put("availability_topic", availability)
                .put("device_class", "humidity")
                .put("state_class", "measurement")
                .put("unit_of_measurement", "%")
                .put("device", device)
            out += Message("homeassistant/sensor/${deviceId}_humidity/config", body.toString())
        }
        return out
    }
}
