package de.rochefort.childmonitor.reliability

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Crash-safe session FSM that persists each transition.
 * Implements CM-REL-002 and CM-REL-006 (process-death recovery checkpoints).
 *
 * Usage:
 *   val fsm = SessionStateMachine(context, "parent-session")
 *   fsm.transition(SessionState.STARTING, mapOf("trace" to traceId))
 *
 * On restart:
 *   val checkpoint = SessionStateMachine.lastCheckpoint(context, "parent-session")
 */
class SessionStateMachine(
    private val context: Context,
    private val name: String
) {

    private val state = AtomicReference(SessionState.IDLE)
    private val lock = Any()

    data class TransitionResult(
        val accepted: Boolean,
        val from: SessionState,
        val to: SessionState,
        val reason: String?
    )

    fun current(): SessionState = state.get()

    fun transition(next: SessionState, attrs: Map<String, Any?> = emptyMap()): TransitionResult {
        synchronized(lock) {
            val cur = state.get()
            if (!cur.canTransitionTo(next)) {
                return TransitionResult(false, cur, next, "illegal transition")
            }
            state.set(next)
            writeCheckpoint(cur, next, attrs)
            return TransitionResult(true, cur, next, null)
        }
    }

    private fun writeCheckpoint(
        from: SessionState,
        to: SessionState,
        attrs: Map<String, Any?>
    ) {
        try {
            val dir = File(context.filesDir, CHECKPOINT_DIR).also { it.mkdirs() }
            val file = File(dir, "${sanitize(name)}.json")
            val sample = MonotonicClock.sample()
            val json = JSONObject().apply {
                put("name", name)
                put("from", from.name)
                put("to", to.name)
                put("wallEpochMs", sample.wallEpochMs)
                put("monoSinceBootMs", sample.monoSinceBootMs)
                for ((k, v) in attrs) put(k, v ?: JSONObject.NULL)
            }
            file.writeText(json.toString())
        } catch (_: IOException) {
            // Checkpoints are best-effort.
        }
    }

    companion object {
        private const val CHECKPOINT_DIR = "session_checkpoints"

        fun lastCheckpoint(context: Context, name: String): JSONObject? {
            val file = File(File(context.filesDir, CHECKPOINT_DIR), "${sanitize(name)}.json")
            if (!file.exists()) return null
            return try {
                JSONObject(file.readText())
            } catch (_: Exception) {
                null
            }
        }

        fun allCheckpoints(context: Context): List<JSONObject> {
            val dir = File(context.filesDir, CHECKPOINT_DIR)
            if (!dir.isDirectory) return emptyList()
            val out = mutableListOf<JSONObject>()
            dir.listFiles()?.forEach { f ->
                try {
                    out.add(JSONObject(f.readText()))
                } catch (_: Exception) {
                    // Skip unreadable files.
                }
            }
            return out
        }

        fun clearCheckpoint(context: Context, name: String) {
            try {
                File(File(context.filesDir, CHECKPOINT_DIR), "${sanitize(name)}.json").delete()
            } catch (_: Exception) {
                // Ignore.
            }
        }

        private fun sanitize(name: String): String =
            name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }
}
