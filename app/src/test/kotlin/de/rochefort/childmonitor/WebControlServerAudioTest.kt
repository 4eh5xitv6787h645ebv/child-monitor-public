package de.rochefort.childmonitor

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.net.Socket
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebControlServerAudioTest {
    @Test fun `both browser parents receive the same frame and closing one preserves the other`() {
        val server = WebControlServer(0, { JSONObject() }, { null }, tokenValidator = { it == "test-parent" })
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val sockets = mutableListOf<Socket>()
        try {
            repeat(2) { sockets += connect(server.listeningPort) }
            awaitListeners(server, 2)
            val frame = byteArrayOf(1, 2, 3, 4)
            server.broadcastAudio(frame, frame.size)
            for (socket in sockets) {
                assertContentEquals(frame, NanoWSD.WebSocketFrame.read(socket.getInputStream()).binaryPayload)
            }
            sockets.first().close()
            awaitListeners(server, 1)
            server.broadcastAudio(frame, frame.size)
            assertContentEquals(frame, NanoWSD.WebSocketFrame.read(sockets.last().getInputStream()).binaryPayload)
        } finally {
            sockets.forEach { it.close() }
            server.stop()
        }
    }

    @Test fun `revoking one browser parent closes only its stream and reports listener changes`() {
        val changes = java.util.concurrent.atomic.AtomicInteger()
        val server = WebControlServer(
            0, { JSONObject() }, { null }, tokenValidator = { it == "parent-a" || it == "parent-b" },
            authenticatedDeviceIdProvider = { it }, audioListenersChangedHandler = { changes.incrementAndGet() },
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val sockets = mutableListOf<Socket>()
        try {
            sockets += connect(server.listeningPort, "parent-a")
            sockets += connect(server.listeningPort, "parent-b")
            awaitListeners(server, 2)
            server.revokeDevice("parent-a")
            awaitListeners(server, 1)
            assertEquals(-1, sockets[0].getInputStream().read())
            val frame = byteArrayOf(4, 5, 6)
            server.broadcastAudio(frame, frame.size)
            assertContentEquals(frame, NanoWSD.WebSocketFrame.read(sockets[1].getInputStream()).binaryPayload)
            sockets[1].close()
            awaitListeners(server, 0)
            val until = System.nanoTime() + 2_000_000_000L
            while (changes.get() < 4 && System.nanoTime() < until) Thread.sleep(5)
            assertEquals(4, changes.get())
        } finally { sockets.forEach { it.close() }; server.stop() }
    }

    @Test fun `revocation between authorization and open cannot leave an orphan browser stream`() {
        val ownerChecks = java.util.concurrent.atomic.AtomicInteger()
        val server = WebControlServer(
            0, { JSONObject() }, { null }, tokenValidator = { true },
            authenticatedDeviceIdProvider = { if (ownerChecks.incrementAndGet() == 1) "parent-a" else null },
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            connect(server.listeningPort).use { socket ->
                assertEquals(-1, socket.getInputStream().read())
                awaitListeners(server, 0)
                assertEquals(2, ownerChecks.get())
            }
        } finally { server.stop() }
    }

    @Test fun `revoking one talkback parent preserves the other speaking connection`() {
        val frames = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        val stopped = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val server = WebControlServer(
            0, { JSONObject() }, { null }, tokenValidator = { it == "parent-a" || it == "parent-b" },
            authenticatedDeviceIdProvider = { it },
            talkbackAudioHandler = { source, _ ->
                val owner = (source as WebControlServer.TalkbackSocket).deviceId!!
                frames.computeIfAbsent(owner) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            },
            talkbackStoppedHandler = { source -> stopped.add((source as WebControlServer.TalkbackSocket).deviceId!!) },
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val sockets = mutableListOf<Socket>()
        try {
            sockets += connect(server.listeningPort, "parent-a", "/ws/talkback")
            sockets += connect(server.listeningPort, "parent-b", "/ws/talkback")
            sockets.forEach {
                de.rochefort.childmonitor.audio.MaskedWebSocketWriter.write(it.getOutputStream(), byteArrayOf(1), 1)
            }
            val ready = System.nanoTime() + 2_000_000_000L
            while (frames.size < 2 && System.nanoTime() < ready) Thread.sleep(5)
            assertEquals(2, frames.size)
            server.revokeDevice("parent-a")
            assertEquals(-1, sockets[0].getInputStream().read())
            de.rochefort.childmonitor.audio.MaskedWebSocketWriter.write(sockets[1].getOutputStream(), byteArrayOf(1), 1)
            val until = System.nanoTime() + 2_000_000_000L
            while ((frames["parent-b"]?.get() != 2 || "parent-a" !in stopped) && System.nanoTime() < until) Thread.sleep(5)
            assertEquals(2, frames["parent-b"]?.get())
            assertEquals(setOf("parent-a"), stopped.toSet())
        } finally { sockets.forEach { it.close() }; server.stop() }
    }

    @Test fun `healthy receive-only browser parents survive inbound HTTP timeout`() {
        val server = WebControlServer(0, { JSONObject() }, { null }, tokenValidator = { it == "test-parent" })
        server.start(250, false)
        val sockets = mutableListOf<Socket>()
        val sender = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "test-browser-audio").apply { isDaemon = true }
        }
        try {
            repeat(2) { sockets += connect(server.listeningPort) }
            awaitListeners(server, 2)
            val payload = byteArrayOf(11, 22, 33)
            sender.scheduleAtFixedRate({ server.broadcastAudio(payload, payload.size) }, 0, 25, java.util.concurrent.TimeUnit.MILLISECONDS)
            val until = System.nanoTime() + 900_000_000L
            var received = 0
            while (System.nanoTime() < until) {
                for (socket in sockets) {
                    val frame = NanoWSD.WebSocketFrame.read(socket.getInputStream())
                    assertEquals(NanoWSD.WebSocketFrame.OpCode.Binary, frame.opCode)
                    assertContentEquals(payload, frame.binaryPayload)
                }
                received++
            }
            assertTrue(received >= 16)
            assertEquals(2, server.audioListenerCount())
        } finally { sender.shutdownNow(); sockets.forEach { it.close() }; server.stop() }
    }

    @Test fun `anonymous incomplete HTTP request retains its bounded read timeout`() {
        val server = WebControlServer(0, { JSONObject() }, { null }, tokenValidator = { it == "test-parent" })
        server.start(250, false)
        try {
            Socket("127.0.0.1", server.listeningPort).use { socket ->
                socket.soTimeout = 1_500
                // Even though authenticated WebSockets are long-lived, an anonymous peer cannot
                // consume a worker indefinitely by connecting without sending HTTP headers.
                socket.getInputStream().readBytes()
            }
        } finally { server.stop() }
    }

    private fun connect(port: Int, token: String = "test-parent", path: String = "/ws/audio"): Socket {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 2_000
        val nonce = java.util.Base64.getEncoder().encodeToString(
            ByteArray(16).also(java.security.SecureRandom()::nextBytes),
        )
        socket.getOutputStream().write((
            "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
            "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
            "Sec-WebSocket-Key: $nonce\r\nAuthorization: Bearer $token\r\n\r\n"
        ).toByteArray())
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val byte = socket.getInputStream().read()
            assertTrue(byte >= 0)
            header.append(byte.toChar())
            assertTrue(header.length < 8_192)
        }
        assertTrue(header.startsWith("HTTP/1.1 101"))
        return socket
    }

    private fun awaitListeners(server: WebControlServer, expected: Int) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (server.audioListenerCount() != expected && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(expected, server.audioListenerCount())
    }
}
