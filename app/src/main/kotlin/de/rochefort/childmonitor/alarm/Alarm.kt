/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.rochefort.childmonitor.R

/**
 * A loud, looping disconnect alarm (#290): plays on the ALARM stream (USAGE_ALARM) so it's
 * audible regardless of media volume, and repeats until silenced — the child reconnects
 * ([reset]), the user acknowledges by opening the parent app ([acknowledge]), or the session
 * ends ([stop]).
 *
 * The MediaPlayer is built and prepared on the **main Looper** (via [mainHandler]) so its
 * async `onPrepared`/`onError` callbacks land deterministically — `start()` is called from
 * the listen thread, which has no Looper. The prepared callback re-checks identity + state
 * under the monitor so a `stop()` that wins the race before preparation can't leave a
 * never-stopping alarm. All public methods are synchronized; safe from any thread.
 */
class Alarm(context: Context) {

    private val app = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var pending = false
    /** Acknowledged-and-suppressed: while true, [start] is a no-op (until [reset]). */
    private var suppressed = false

    /** Arm the looping alarm. No-op if already armed, mid-arm, or acknowledged. */
    @Synchronized
    fun start() {
        if (suppressed || player != null || pending) return
        pending = true
        mainHandler.post { buildAndPlay() }
    }

    /** Stop the sound; leaves the acknowledged state unchanged. */
    @Synchronized
    fun stop() {
        pending = false
        releasePlayer()
    }

    /** User acknowledged (opened the app): silence + suppress re-arming until [reset]. */
    @Synchronized
    fun acknowledge() {
        suppressed = true
        pending = false
        releasePlayer()
    }

    /** Connection (re)established: silence + clear suppression so a future drop re-alarms. */
    @Synchronized
    fun reset() {
        suppressed = false
        pending = false
        releasePlayer()
    }

    @Synchronized
    private fun buildAndPlay() {
        pending = false
        if (suppressed || player != null) return
        try {
            val afd = app.resources.openRawResourceFd(R.raw.upward_beep_chromatic_fifths) ?: return
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                afd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                isLooping = true
                setOnPreparedListener { mp ->
                    synchronized(this@Alarm) {
                        // Only start if this is still the current, un-stopped, un-acknowledged player.
                        if (player === mp && !suppressed) {
                            mp.start()
                            Log.i(TAG, "Persistent alarm sounding (looping on ALARM stream)")
                        } else {
                            try { mp.release() } catch (_: Exception) {}
                            if (player === mp) player = null
                        }
                    }
                }
                setOnErrorListener { mp, what, extra ->
                    Log.w(TAG, "alarm MediaPlayer error what=$what extra=$extra")
                    synchronized(this@Alarm) {
                        try { mp.release() } catch (_: Exception) {}
                        if (player === mp) player = null
                    }
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to arm persistent alarm", e)
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        player?.let { p ->
            try { if (p.isPlaying) p.stop() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
            Log.i(TAG, "Persistent alarm stopped")
        }
        player = null
    }

    companion object {
        private const val TAG = "Alarm"
    }
}
