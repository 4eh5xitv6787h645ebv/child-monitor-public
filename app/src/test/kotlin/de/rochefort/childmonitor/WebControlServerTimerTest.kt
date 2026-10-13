package de.rochefort.childmonitor

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebControlServerTimerTest {
    private lateinit var server: WebControlServer
    private var sootherDuration: Long? = null
    private var nightDuration: Long? = null

    @Before fun startServer() {
        server = WebControlServer(
            0,
            statusProvider = { JSONObject() },
            snapshotProvider = { null },
            sootherHandler = { action, _, _, duration ->
                assertEquals("timer", action)
                sootherDuration = duration
                JSONObject().put("success", true)
            },
            nightLightHandler = { on, _, _, duration ->
                assertNull(on)
                nightDuration = duration
                JSONObject().put("success", true)
            },
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    @After fun stopServer() = server.stop()

    @Test fun `independent light and sound timer durations reach their own handlers`() {
        assertEquals(200, post("/api/nightlight?durationMs=3600000"))
        assertEquals(3_600_000L, nightDuration)
        assertNull(sootherDuration)

        assertEquals(200, post("/api/soother?action=timer&durationMs=1800000"))
        assertEquals(1_800_000L, sootherDuration)
        assertEquals(3_600_000L, nightDuration)
    }

    @Test fun `malformed timer duration is rejected before either handler runs`() {
        assertEquals(400, post("/api/nightlight?durationMs=tomorrow"))
        assertEquals(400, post("/api/soother?action=timer&durationMs=soon"))
        assertNull(nightDuration)
        assertNull(sootherDuration)
    }

    private fun post(path: String): Int {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.doOutput = true
        connection.outputStream.use { it.write(ByteArray(0)) }
        val code = connection.responseCode
        (if (code in 200..299) connection.inputStream else connection.errorStream)?.close()
        connection.disconnect()
        return code
    }
}
