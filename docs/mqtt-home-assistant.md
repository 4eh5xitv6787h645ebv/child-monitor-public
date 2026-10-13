# MQTT and Home Assistant

The child can publish local monitoring state to an MQTT broker and create Home Assistant entities
through MQTT discovery. The bridge is opt-in and disabled by default.

Configure it on the parent while connected to the child: **Child controls → Alerts & webhooks →
MQTT / Home Assistant**. Use an `ssl://host:port` broker URI when supplying a username or password.
Anonymous `tcp://` is available for a trusted local network, but broker credentials are never sent
over plaintext. The password is encrypted with the child's Android Keystore and is never returned
to the parent or included in status/discovery payloads.

The child publishes retained availability and current connection state beneath the configured base
topic. Alert-worthy sound and motion events use short `ON` pulses. Home Assistant discovery creates:

- sound and motion binary sensors;
- a parent-connected connectivity sensor;
- temperature and humidity sensors when their input topics are configured.

For an external room sensor, enter its exact MQTT temperature and/or humidity state topic. Payloads
must be plain numeric Celsius or relative-humidity values. Configure safe minimum and maximum values
in the same screen. Fresh out-of-range readings generate a de-duplicated critical notification on the
parent and are recorded in the child's local event timeline.

The bridge subscribes only to the exact configured topics; MQTT wildcards are rejected. Broker
settings are accepted by the child only over its pinned TLS control channel (or on-device loopback).
Do not expose the broker or child monitoring ports to the public internet.
