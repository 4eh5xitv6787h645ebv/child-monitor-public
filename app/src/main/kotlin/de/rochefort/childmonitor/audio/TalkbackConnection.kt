package de.rochefort.childmonitor.audio

import java.io.IOException
import java.net.Socket

/** Cancelling one press-to-talk attempt must never close a later attempt's connection. */
internal class TalkbackConnection {
    @Volatile var isCancelled = false
        private set
    private var socket: Socket? = null

    @Synchronized fun attach(connection: Socket) {
        if (isCancelled) {
            closeAsync(connection)
            throw IOException("Talkback cancelled")
        }
        // A TLS socket wraps and owns the preceding plain socket.
        socket = connection
    }

    fun cancel() {
        val owned = synchronized(this) {
            if (isCancelled) return
            isCancelled = true
            socket.also { socket = null }
        }
        owned?.let(::closeAsync)
    }

    private fun closeAsync(connection: Socket) {
        // SSL close can wait for a blocked writer; never hold up the button's UI thread.
        Thread({ try { connection.close() } catch (_: Exception) {} }, "talkback-close")
            .apply { isDaemon = true }.start()
    }
}
