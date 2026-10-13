package de.rochefort.childmonitor.cast

import de.rochefort.childmonitor.events.EventHookPolicy
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** Secure, bounded parser for untrusted UPnP device descriptions received from the LAN. */
object DlnaDeviceDescriptionParser {
    const val MAX_DESCRIPTION_BYTES = 256 * 1024

    fun parse(xml: ByteArray, locationUrl: String, responderAddress: InetAddress): DlnaRenderer? {
        if (xml.isEmpty() || xml.size > MAX_DESCRIPTION_BYTES) return null
        val location = safeLocation(locationUrl, responderAddress) ?: return null
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                setExpandEntityReferences(false)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                try { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") } catch (_: Exception) {}
                try { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") } catch (_: Exception) {}
            }
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
            val devices = document.getElementsByTagNameNS("*", "device")
            for (i in 0 until devices.length) {
                val device = devices.item(i) as? Element ?: continue
                val type = firstText(device, "deviceType") ?: continue
                if (!type.startsWith("urn:schemas-upnp-org:device:MediaRenderer:")) continue
                val friendlyName = firstText(device, "friendlyName")
                    ?.filterNot { it.isISOControl() }
                    ?.trim()
                    ?.take(128)
                    ?.takeIf { it.isNotBlank() }
                    .orEmpty()
                val services = device.getElementsByTagNameNS("*", "service")
                for (j in 0 until services.length) {
                    val service = services.item(j) as? Element ?: continue
                    val serviceType = firstText(service, "serviceType") ?: continue
                    if (!serviceType.startsWith("urn:schemas-upnp-org:service:AVTransport:")) continue
                    val rawControl = firstText(service, "controlURL")?.trim() ?: continue
                    if (rawControl.length !in 1..MAX_CONTROL_URL_CHARS) continue
                    val control = location.resolve(rawControl)
                    if (!safeControlUrl(control, location)) continue
                    return DlnaRenderer(friendlyName, control.toASCIIString(), responderAddress.hostAddress ?: return null)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun safeLocation(raw: String, responderAddress: InetAddress): URI? {
        return try {
            val uri = URI(raw.trim())
            if (!uri.scheme.equals("http", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null ||
            (uri.port != -1 && uri.port !in 1..65535) || uri.fragment != null ||
                !isNumericHost(uri.host)
            ) {
                null
            } else {
                val resolved = InetAddress.getAllByName(uri.host).toList()
                if (resolved.isEmpty() || resolved.none { it == responderAddress } ||
                    !EventHookPolicy.allPrivate(resolved) || !EventHookPolicy.isPrivateAddress(responderAddress)
                ) null else uri
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun safeControlUrl(control: URI, location: URI): Boolean {
        if (!control.scheme.equals("http", ignoreCase = true) || control.host.isNullOrBlank() ||
            control.userInfo != null || control.fragment != null ||
            (control.port != -1 && control.port !in 1..65535)
        ) return false
        // A renderer may expose AVTransport on another port, but not another host. This prevents
        // an untrusted description from turning the parent into a LAN SSRF proxy.
        return control.host.equals(location.host, ignoreCase = true)
    }

    private fun firstText(parent: Element, localName: String): String? {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        return if (nodes.length == 0) null else nodes.item(0)?.textContent
    }

    private fun isNumericHost(host: String): Boolean {
        val value = host.removePrefix("[").removeSuffix("]")
        return if (value.contains(':')) {
            value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '%' }
        } else {
            value.isNotBlank() && value.all { it.isDigit() || it == '.' }
        }
    }

    private const val MAX_CONTROL_URL_CHARS = 2_048
}
