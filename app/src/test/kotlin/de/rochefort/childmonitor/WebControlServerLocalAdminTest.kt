package de.rochefort.childmonitor

import de.rochefort.childmonitor.security.LocalControlAuthority
import fi.iki.elonen.NanoHTTPD
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebControlServerLocalAdminTest {
    @Test fun loopbackRequiresOwnProcessProofOrParentBearer() {
        val revocations = AtomicInteger()
        val server = server(revocations)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            assertEquals(401, request(server, "/api/status"))
            assertEquals(401, request(server, "/api/pairing/devices"))
            assertEquals(403, request(server, "/api/pairing/challenge"))
            assertEquals(401, request(server, "/api/pairing/revoke?deviceId=parent-a", "POST"))
            assertEquals(0, revocations.get())
            assertEquals(200, request(server, "/api/status", headers = mapOf("Authorization" to "Bearer test-parent")))
            assertEquals(403, request(server, "/api/pairing/challenge", headers = mapOf("Authorization" to "Bearer test-parent")))
            val localProof = mapOf(LocalControlAuthority.HEADER to LocalControlAuthority.token)
            assertEquals(200, request(server, "/api/pairing/challenge", headers = localProof))
            assertEquals(200, request(server, "/api/pairing/devices", headers = localProof))
            assertEquals(200, request(server, "/api/pairing/revoke?deviceId=parent-a", headers = localProof))
            assertEquals(1, revocations.get())
        } finally { server.stop() }
    }

    @Test fun requestHeadersCannotForgeActualLoopbackPeer() {
        val revocations = AtomicInteger()
        val server = server(revocations)
        val session = Proxy.newProxyInstance(
            NanoHTTPD.IHTTPSession::class.java.classLoader,
            arrayOf(NanoHTTPD.IHTTPSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getUri" -> "/api/pairing/devices"
                "getMethod" -> NanoHTTPD.Method.GET
                "getHeaders" -> mutableMapOf(
                    "remote-addr" to "127.0.0.1", "x-forwarded-for" to "127.0.0.1",
                    LocalControlAuthority.HEADER to LocalControlAuthority.token,
                )
                "getRemoteIpAddress" -> "192.168.1.50"
                "getParameters", "getParms" -> mutableMapOf<String, String>()
                "getQueryParameterString" -> ""
                else -> null
            }
        } as NanoHTTPD.IHTTPSession
        assertEquals(401, server.serve(session).status.requestStatus)
        assertEquals(0, revocations.get())
    }

    @Test fun invalidLocalProofNeverGrantsAdminAccess() {
        val server = server(AtomicInteger())
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val wrong = mapOf(LocalControlAuthority.HEADER to "invalid-local-proof")
            assertEquals(401, request(server, "/api/pairing/devices", headers = wrong))
            assertEquals(403, request(server, "/api/pairing/challenge", headers = wrong))
        } finally { server.stop() }
    }

    private fun server(revocations: AtomicInteger) = WebControlServer(
        0, { JSONObject() }, { null },
        pairingChallengeProvider = { JSONObject().put("code", "test-code") },
        pairedDevicesProvider = { JSONObject().put("devices", org.json.JSONArray()) },
        pairingRevokeHandler = { revocations.incrementAndGet(); true },
        tokenValidator = { it == "test-parent" },
        localAdminValidator = LocalControlAuthority::validates,
    )

    private fun request(
        server: WebControlServer, path: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
    ): Int {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            headers.forEach(connection::setRequestProperty)
            connection.responseCode
        } finally { connection.disconnect() }
    }
}
