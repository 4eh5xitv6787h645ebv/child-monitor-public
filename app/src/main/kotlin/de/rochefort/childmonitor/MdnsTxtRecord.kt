package de.rochefort.childmonitor

/**
 * Builds the TXT attributes advertised for the child's NSD/mDNS service (#209).
 *
 * The service is broadcast to every device on the LAN, so the TXT record must not
 * leak the device hardware. It always carries the opaque stable [deviceId] (which
 * a paired parent matches against, from the QR) and the protocol version. The
 * human-readable name is advertised ONLY when the user has explicitly set a custom
 * one — the [android.os.Build.MODEL] default ("Pixel 8 Pro", "SM-A556E", …) is
 * never broadcast to passive observers. The discovery UI shows the opaque service
 * name regardless, so omitting the default name costs nothing functionally.
 *
 * Pure (no Android), so the "model is never broadcast" invariant is unit-tested.
 */
object MdnsTxtRecord {

    fun attributes(
        deviceId: String,
        customName: String?,
        audioTlsPort: Int? = null
    ): Map<String, String> = buildMap {
        val validAudioTlsPort = audioTlsPort?.takeIf { it in 1..65535 }
        put("id", deviceId)
        put("v", if (validAudioTlsPort != null) "2" else "1")
        if (validAudioTlsPort != null) put("ap", validAudioTlsPort.toString())
        val trimmed = customName?.trim()
        if (!trimmed.isNullOrEmpty()) put("name", trimmed)
    }
}
