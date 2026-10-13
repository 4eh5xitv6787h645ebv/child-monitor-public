package de.rochefort.childmonitor.cast

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** Discovers UPnP MediaRenderer devices and resolves their AVTransport control endpoint. */
class DlnaDiscoveryClient {
    fun discover(timeoutMs: Int = 4_000): List<DlnaRenderer> {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000, 10_000)
        val candidates = LinkedHashMap<String, InetAddress>()
        DatagramSocket().use { socket ->
            socket.reuseAddress = true
            socket.soTimeout = 350
            sendSearch(socket, MEDIA_RENDERER_TARGET)
            sendSearch(socket, "ssdp:all")
            val buffer = ByteArray(MAX_SSDP_PACKET_BYTES)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val response = String(packet.data, packet.offset, packet.length, Charsets.ISO_8859_1)
                    val location = parseHeader(response, "location") ?: continue
                    if (!candidates.containsKey(location) && candidates.size < MAX_CANDIDATES) {
                        candidates[location] = packet.address
                    }
                } catch (_: SocketTimeoutException) {
                    // Keep listening until the total deadline; renderers answer at different MX delays.
                } catch (_: Exception) {
                    break
                }
            }
        }
        return candidates.mapNotNull { (location, responder) -> resolve(location, responder) }
            .distinctBy { it.controlUrl }
            .sortedBy { it.friendlyName.lowercase(java.util.Locale.ROOT) }
    }

    private fun sendSearch(socket: DatagramSocket, target: String) {
        val request = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: $target\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        socket.send(DatagramPacket(request, request.size, SSDP_ADDRESS, SSDP_PORT))
    }

    private fun resolve(location: String, responder: InetAddress): DlnaRenderer? {
        val safe = DlnaDeviceDescriptionParser.safeLocation(location, responder) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(safe.toASCIIString()).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.setRequestProperty("Accept", "text/xml, application/xml")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val declaredLength = connection.contentLength
            if (declaredLength > DlnaDeviceDescriptionParser.MAX_DESCRIPTION_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    if (output.size() + read > DlnaDeviceDescriptionParser.MAX_DESCRIPTION_BYTES) return null
                    output.write(chunk, 0, read)
                }
                output.toByteArray()
            }
            DlnaDeviceDescriptionParser.parse(bytes, location, responder)
        } catch (_: Exception) {
            null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    companion object {
        private val SSDP_ADDRESS = InetAddress.getByName("239.255.255.250")
        private const val SSDP_PORT = 1900
        private const val MAX_SSDP_PACKET_BYTES = 16 * 1024
        private const val MAX_CANDIDATES = 8
        private const val MEDIA_RENDERER_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"

        fun parseHeader(response: String, wantedName: String): String? {
            if (response.length > MAX_SSDP_PACKET_BYTES || !response.startsWith("HTTP/1.1 200", true)) return null
            return response.lineSequence().drop(1).mapNotNull { line ->
                val split = line.indexOf(':')
                if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim()
            }.firstOrNull { it.first.equals(wantedName, ignoreCase = true) }?.second
                ?.takeIf { it.length in 1..2_048 && !it.contains('\r') && !it.contains('\n') }
        }
    }
}
