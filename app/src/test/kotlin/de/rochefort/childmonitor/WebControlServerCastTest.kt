package de.rochefort.childmonitor

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebControlServerCastTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
    private val grant = "a".repeat(48)
    private lateinit var server: WebControlServer

    @Before fun startServer() {
        server = WebControlServer(
            0,
            statusProvider = { JSONObject() },
            snapshotProvider = { jpeg },
            tokenValidator = { it == "parent-bearer" },
            castSessionIssuer = { bearer ->
                if (bearer == "parent-bearer") JSONObject().put("token", grant) else null
            },
            castTokenValidator = { token, _ -> token == grant },
            castViewerTextProvider = {
                "Child Monitor local display" to "Local video only · audio stays on the parent phone"
            },
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    @After fun stopServer() {
        server.stop()
    }

    @Test fun `cast snapshot requires scoped grant even when parent bearer is supplied`() {
        assertEquals(401, request("/cast/snapshot").first)
        assertEquals(401, request("/cast/snapshot", bearer = "parent-bearer").first)
        val allowed = request("/cast/snapshot?cast=$grant")
        assertEquals(200, allowed.first)
        assertContentEquals(jpeg, allowed.second)
    }

    @Test fun `cast viewer is video only and sends privacy headers`() {
        val connection = open("/cast?cast=$grant")
        assertEquals(200, connection.responseCode)
        val page = connection.inputStream.bufferedReader().readText()
        assertTrue(page.contains("/cast/stream.mjpeg?cast=$grant"))
        assertTrue(page.contains("audio stays on the parent phone"))
        assertFalse(page.contains("/ws/audio"))
        assertEquals("no-referrer", connection.getHeaderField("Referrer-Policy"))
        assertEquals("DENY", connection.getHeaderField("X-Frame-Options"))
        connection.disconnect()
    }

    @Test fun `grant issuance still requires the parent Authorization header`() {
        assertEquals(401, request("/api/cast/session", method = "POST").first)
        val issued = request("/api/cast/session", method = "POST", bearer = "parent-bearer")
        assertEquals(200, issued.first)
        assertEquals(grant, JSONObject(issued.second.toString(Charsets.UTF_8)).getString("token"))
    }

    private fun request(
        path: String,
        method: String = "GET",
        bearer: String? = null,
    ): Pair<Int, ByteArray> {
        val connection = open(path, method, bearer)
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        connection.disconnect()
        return code to body
    }

    private fun open(
        path: String,
        method: String = "GET",
        bearer: String? = null,
    ): HttpURLConnection = (URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection)
        .apply {
            requestMethod = method
            connectTimeout = 2_000
            readTimeout = 2_000
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            if (method == "POST") {
                doOutput = true
                outputStream.use { it.write(ByteArray(0)) }
            }
        }
}
