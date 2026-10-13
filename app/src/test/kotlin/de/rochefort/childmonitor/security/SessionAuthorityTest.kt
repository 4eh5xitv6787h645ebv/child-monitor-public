package de.rochefort.childmonitor.security

import android.content.SharedPreferences
import de.rochefort.childmonitor.WebControlServer
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.lang.reflect.Proxy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionAuthorityTest {
    private val parentA = "parent-00000000-0000-0000-0000-000000000001"
    private val parentB = "parent-00000000-0000-0000-0000-000000000002"

    @Test fun `two phones scanning one QR keep independent access through rotation and restart`() {
        val prefs = memoryPreferences()
        val authority = SessionAuthority(prefs)
        val qr = authority.issueToken("child-id", tlsAudioRequired = true).getString("token")
        val a = assertNotNull(authority.bootstrapParent(qr, parentA))
        val b = assertNotNull(authority.bootstrapParent(qr, parentB))
        assertNotEquals(a.getString("token"), b.getString("token"))
        assertNotEquals(a.getString("refreshToken"), b.getString("refreshToken"))
        assertEquals(parentA, authority.validate(a.getString("token"))?.deviceId)
        assertEquals(parentB, authority.validate(b.getString("token"))?.deviceId)
        assertNotNull(authority.rotateToken(a.getString("token")))
        assertNotNull(authority.validate(b.getString("token")))
        assertNotNull(authority.validate(qr))

        val restarted = SessionAuthority(prefs)
        for (pair in listOf(a, b)) {
            val renewed = assertNotNull(restarted.redeemRefreshToken(
                pair.getString("deviceId"), pair.getString("refreshToken")))
            assertTrue(assertNotNull(restarted.validate(renewed.getString("token"))).tlsAudioRequired)
        }
        restarted.revokeAllForDevice(parentA)
        assertNull(restarted.redeemRefreshToken(parentA, a.getString("refreshToken")))
        assertNotNull(restarted.redeemRefreshToken(parentB, b.getString("refreshToken")))
        assertNull(restarted.redeemRefreshToken(parentA, b.getString("refreshToken")))
    }

    @Test fun `HTTP QR exchange forwards distinct parent identities and survives bearer expiry`() {
        val authority = SessionAuthority(memoryPreferences())
        val qr = authority.issueToken("child-id", true).getString("token")
        val server = WebControlServer(
            0, statusProvider = { JSONObject() }, snapshotProvider = { null },
            tokenValidator = { authority.validate(it) != null },
            refreshCredentialIssuer = { bearer, parent ->
                if (parent == null) null else authority.bootstrapParent(bearer, parent)
            },
            refreshRedeemer = { device, refresh -> authority.redeemRefreshToken(device, refresh) },
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val pairs = listOf(parentA, parentB).map { parent ->
                post(server.listeningPort, "/api/session/refresh-credential", mapOf(
                    "Authorization" to "Bearer $qr", "X-Parent-Device-Id" to parent,
                )).also { assertEquals(parent, it.getString("deviceId")) }
            }
            for (pair in pairs) {
                authority.revokeToken(pair.getString("token"))
                val renewed = post(server.listeningPort,
                    "/api/session/refresh?deviceId=${pair.getString("deviceId")}",
                    mapOf("X-Refresh-Token" to pair.getString("refreshToken")))
                assertEquals(pair.getString("deviceId"),
                    authority.validate(renewed.getString("token"))?.deviceId)
            }
        } finally { server.stop() }
    }

    private fun post(port: Int, path: String, headers: Map<String, String>): JSONObject {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            assertEquals(200, conn.responseCode)
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }

    @Test fun `legacy shared identity credentials no longer replace one another`() {
        val prefs = memoryPreferences()
        val authority = SessionAuthority(prefs)
        val a = authority.issueRefreshToken("legacy-child").getString("refreshToken")
        val b = authority.issueRefreshToken("legacy-child").getString("refreshToken")
        val restarted = SessionAuthority(prefs)
        assertEquals(2, restarted.refreshCredentialCount())
        assertNotNull(restarted.redeemRefreshToken("legacy-child", a))
        assertNotNull(restarted.redeemRefreshToken("legacy-child", b))
        restarted.revokeAllForDevice("legacy-child")
        assertNull(restarted.redeemRefreshToken("legacy-child", a))
        assertNull(restarted.redeemRefreshToken("legacy-child", b))
    }

    @Test fun `bootstrap rejects invalid identities and expired or revoked bearers`() {
        val authority = SessionAuthority(memoryPreferences())
        val qr = authority.issueToken("child-id").getString("token")
        assertNull(authority.bootstrapParent(qr, "child-id"))
        assertNull(authority.bootstrapParent(qr, "parent-\r\nheader"))
        assertEquals(0, authority.refreshCredentialCount())
        authority.revokeToken(qr)
        assertNull(authority.bootstrapParent(qr, parentA))
    }

    @Test fun `permanent grant waits for successful commit and a failed new parent preserves existing access`() {
        var writable = true
        var commits = 0
        val prefs = memoryPreferences { commits++; writable }
        val authority = SessionAuthority(prefs)
        val qr = authority.issueToken("child-id", true).getString("token")
        val a = assertNotNull(authority.bootstrapParent(qr, parentA))
        assertTrue(commits > 0, "Permanent grant must be committed before returning its secret")
        writable = false
        kotlin.test.assertFailsWith<java.io.IOException> { authority.bootstrapParent(qr, parentB) }
        assertEquals(1, authority.refreshCredentialCount())
        val reloaded = SessionAuthority(prefs)
        assertEquals(1, reloaded.refreshCredentialCount())
        // Space/storage failure must not revoke a phone that already has a durable credential.
        assertNotNull(reloaded.redeemRefreshToken(parentA, a.getString("refreshToken")))
        writable = true
        assertNotNull(authority.bootstrapParent(qr, parentB))
        assertEquals(2, SessionAuthority(prefs).refreshCredentialCount())
    }

    @Test fun `HTTP storage failure is retryable and never returns a permanent secret or false auth rejection`() {
        val authority = SessionAuthority(memoryPreferences { false })
        val qr = authority.issueToken("child-id", true).getString("token")
        val server = WebControlServer(
            0, { JSONObject() }, { null }, tokenValidator = { authority.validate(it) != null },
            refreshCredentialIssuer = { bearer, parent -> authority.bootstrapParent(bearer, parent!!) },
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val connection = URL("http://127.0.0.1:${server.listeningPort}/api/session/refresh-credential")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                connection.setRequestProperty("Authorization", "Bearer $qr")
                connection.setRequestProperty("X-Parent-Device-Id", parentA)
                assertEquals(503, connection.responseCode)
                val body = JSONObject(connection.errorStream.bufferedReader().use { it.readText() })
                assertEquals("credential_storage_unavailable", body.getString("error"))
                kotlin.test.assertFalse(body.has("refreshToken"))
                assertNotNull(authority.validate(qr))
                assertEquals(0, authority.refreshCredentialCount())
            } finally { connection.disconnect() }
        } finally { server.stop() }
    }

    @Test fun `revocation can identify a permanent owner after all short-lived bearers disappear`() {
        val authority = SessionAuthority(memoryPreferences())
        val qr = authority.issueToken("child-id", true).getString("token")
        val a = assertNotNull(authority.bootstrapParent(qr, parentA))
        val b = assertNotNull(authority.bootstrapParent(qr, parentB))
        authority.revokeToken(a.getString("token"))
        assertTrue(authority.hasCredentialsForDevice(parentA))
        kotlin.test.assertFalse(authority.hasCredentialsForDevice("unknown-parent"))
        authority.revokeAllForDevice(parentA)
        kotlin.test.assertFalse(authority.hasCredentialsForDevice(parentA))
        assertNull(authority.redeemRefreshToken(parentA, a.getString("refreshToken")))
        assertTrue(authority.hasCredentialsForDevice(parentB))
        assertNotNull(authority.redeemRefreshToken(parentB, b.getString("refreshToken")))
    }

    /** Real serialization/persistence code runs against a minimal in-memory preference adapter. */
    private fun memoryPreferences(commitSucceeds: () -> Boolean = { true }): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        val pending = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { pending[args!![0] as String] = args[1]; proxy }
                "remove" -> { pending[args!![0] as String] = null; proxy }
                "apply", "commit" -> {
                    pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    pending.clear()
                    if (method.name == "commit") commitSucceeds() else null
                }
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preference method ${method.name}")
            }
        } as SharedPreferences
    }
}
