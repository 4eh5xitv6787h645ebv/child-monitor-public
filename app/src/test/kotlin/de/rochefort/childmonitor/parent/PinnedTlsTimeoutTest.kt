package de.rochefort.childmonitor.parent

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PinnedTlsTimeoutTest {
    @Test fun unresponsiveTlsPeerTimesOutAndClosesOwnedSocket() {
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { plain ->
                server.accept().use {
                    plain.soTimeout = 150
                    val attempt = FutureTask {
                        assertThrows(IOException::class.java) {
                            PinnedTls.upgrade(plain, "127.0.0.1", server.localPort, "0".repeat(64))
                        }
                    }
                    Thread(attempt, "tls-timeout-test").apply { isDaemon = true }.start()
                    attempt.get(3, TimeUnit.SECONDS)
                    assertTrue("Failed TLS negotiation must release its underlying socket", plain.isClosed)
                }
            }
        }
    }

    @Test fun callerWithoutReadTimeoutStillHasBoundedHandshake() {
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { plain ->
                server.accept().use {
                    assertEquals(0, plain.soTimeout)
                    val attempt = FutureTask {
                        assertThrows(IOException::class.java) {
                            PinnedTls.upgrade(plain, "127.0.0.1", server.localPort, "0".repeat(64))
                        }
                    }
                    Thread(attempt, "tls-default-timeout-test").apply { isDaemon = true }.start()
                    attempt.get(5, TimeUnit.SECONDS)
                    assertTrue(plain.isClosed)
                }
            }
        }
    }
}
