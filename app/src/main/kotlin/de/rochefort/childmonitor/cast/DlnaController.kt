package de.rochefort.childmonitor.cast

import de.rochefort.childmonitor.events.EventHookPolicy
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

/** Sends the standard UPnP AVTransport commands used by DLNA televisions and renderers. */
class DlnaController {
    fun play(renderer: DlnaRenderer, mediaUrl: String, mimeType: String, displayTitle: String): Boolean {
        if (!isSafeMediaUrl(mediaUrl) || mimeType !in ALLOWED_MIME_TYPES) return false
        val metadata = didlMetadata(mediaUrl, mimeType, displayTitle)
        return soap(
            renderer,
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${xmlEscape(mediaUrl)}</CurrentURI>" +
                "<CurrentURIMetaData>${xmlEscape(metadata)}</CurrentURIMetaData>",
        ) && soap(renderer, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    fun stop(renderer: DlnaRenderer): Boolean =
        soap(renderer, "Stop", "<InstanceID>0</InstanceID>")

    private fun soap(renderer: DlnaRenderer, action: String, arguments: String): Boolean {
        if (action !in ALLOWED_ACTIONS) return false
        val control = try { URI(renderer.controlUrl) } catch (_: Exception) { return false }
        if (!control.scheme.equals("http", ignoreCase = true) || control.host.isNullOrBlank()) return false
        val isRendererHost = try {
            control.host.equals(renderer.address, ignoreCase = true) ||
                InetAddress.getAllByName(control.host).any { it.hostAddress == renderer.address }
        } catch (_: Exception) {
            false
        }
        if (!isRendererHost) return false
        val body = soapEnvelope(action, arguments).toByteArray(Charsets.UTF_8)
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(renderer.controlUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_500
            connection.readTimeout = 2_500
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPAction", "\"$AV_TRANSPORT#$action\"")
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            try {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { input ->
                        val discard = ByteArray(4 * 1024)
                        var remaining = MAX_SOAP_RESPONSE_BYTES
                        while (remaining > 0) {
                            val read = input.read(discard, 0, minOf(discard.size, remaining))
                            if (read < 0) break
                            remaining -= read
                        }
                    }
            } catch (_: Exception) {}
            code in 200..299
        } catch (_: Exception) {
            false
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    companion object {
        const val MIME_MJPEG = "multipart/x-mixed-replace"
        const val MIME_JPEG = "image/jpeg"
        private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        private val ALLOWED_ACTIONS = setOf("SetAVTransportURI", "Play", "Stop")
        private val ALLOWED_MIME_TYPES = setOf(MIME_MJPEG, MIME_JPEG)
        private const val MAX_SOAP_RESPONSE_BYTES = 64 * 1024

        fun isSafeMediaUrl(raw: String): Boolean = try {
            val uri = URI(raw)
            uri.scheme.equals("http", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.fragment == null && EventHookPolicy.allPrivate(InetAddress.getAllByName(uri.host).toList())
        } catch (_: Exception) {
            false
        }

        fun didlMetadata(mediaUrl: String, mimeType: String, displayTitle: String): String =
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"child-monitor\" parentID=\"0\" restricted=\"1\">" +
                "<dc:title>${xmlEscape(displayTitle.take(128))}</dc:title>" +
                "<upnp:class>object.item.imageItem.photo</upnp:class>" +
                "<res protocolInfo=\"http-get:*:${xmlEscape(mimeType)}:*\">${xmlEscape(mediaUrl)}</res>" +
                "</item></DIDL-Lite>"

        fun soapEnvelope(action: String, arguments: String): String =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:$action xmlns:u=\"$AV_TRANSPORT\">$arguments</u:$action></s:Body></s:Envelope>"

        fun xmlEscape(value: String): String = buildString(value.length + 16) {
            value.forEach {
                when (it) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(it)
                }
            }
        }
    }
}
