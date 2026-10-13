package de.rochefort.childmonitor

import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A bounded replacement for NanoHTTPD's `DefaultAsyncRunner` (#207).
 *
 * The default runner spawns one unbounded thread per accepted connection, so a
 * LAN attacker can open many simultaneous connections and force unbounded thread
 * creation. This is a thin adapter over [BoundedRunCore]: **under** the cap it
 * behaves exactly like the default (track the handler, run it on a daemon thread;
 * the handler's own `closed()` callback frees the slot), and **over** the cap it
 * refuses by closing the connection's socket instead of spawning a thread. Normal
 * operation (a handful of parents) never reaches the cap, so this is transparent
 * for legitimate use and only bites a flood.
 */
class BoundedAsyncRunner(maxConcurrent: Int) : NanoHTTPD.AsyncRunner {

    private val core = BoundedRunCore<NanoHTTPD.ClientHandler>(maxConcurrent) { handler, name ->
        Thread(handler, name).apply {
            isDaemon = true
            start()
        }
    }

    /** Log the cap being hit once per process, not once per refused connection —
     *  logging every refusal would let the flood amplify into a log-write DoS. */
    private val capWarned = AtomicBoolean(false)

    override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
        if (!core.submit(clientHandler, "cm-http")) {
            if (capWarned.compareAndSet(false, true)) {
                LogManager.w(
                    LogManager.LogCategory.CONNECTION,
                    "HTTP connection cap reached at least once — refusing excess connections to bound thread use (#207)"
                )
            }
            try { clientHandler.close() } catch (_: Exception) { }
        }
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        core.completed(clientHandler)
    }

    override fun closeAll() {
        // Snapshot first: each close() triggers closed(), which mutates the live set.
        for (handler in core.runningSnapshot()) {
            try { handler.close() } catch (_: Exception) { }
        }
    }
}
