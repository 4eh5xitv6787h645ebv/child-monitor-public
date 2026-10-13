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
package de.rochefort.childmonitor.nighttimeline

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Wires the live monitoring session into the night timeline (#283): the parent's audio
 * thread feeds [onAudioSamples] every packet, and the snapshot loop feeds [onMotion]; each
 * completed minute is classified and persisted to [NightMetricsStore]. All on-device.
 *
 * Owned by ListenService (a foreground service), so noise sampling keeps running with the
 * screen off — exactly what a night timeline needs. Synchronized because audio and snapshot
 * callbacks arrive on different threads.
 */
class NightRecorder(context: Context) {

    private val appContext = context.applicationContext
    private val store = NightMetricsStore(appContext)
    private val aggregator = MinuteAggregator()
    private val lock = Any()
    // All DB writes run here so they never block the audio thread (a WAL checkpoint / disk
    // stall on the audio path could glitch playback). Single-threaded → writes stay ordered.
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NightRecorder-io").apply { isDaemon = true }
    }

    /** "Awake" loudness threshold in dBFS; peaks at/above this mark a NOISY minute. */
    @Volatile var awakeDbfs: Double = DEFAULT_AWAKE_DBFS
    /** Motion %-changed-cells at/above this (and quiet audio) mark an AWAKE minute. */
    @Volatile var motionThresholdPercent: Int = DEFAULT_MOTION_THRESHOLD

    init {
        // #285: seed live capture thresholds from the user's sensitivity setting.
        val s = Sensitivity.load(appContext)
        awakeDbfs = s.awakeDbfs
        motionThresholdPercent = s.motionThresholdPercent
        // Prune beyond retention on the IO thread (serialized with persists, off startup path).
        io.execute {
            try {
                val cutoff = System.currentTimeMillis() - NightMetricsStore.RETENTION_DAYS * 24L * 60L * 60L * 1000L
                store.deleteOlderThan(cutoff)
            } catch (e: Exception) {
                Log.w(TAG, "retention prune failed", e)
            }
        }
    }

    fun onAudioSamples(samples: ShortArray, len: Int) {
        val dbfs = AudioLevel.rmsDbfs(samples, len)
        val emitted: MinuteBucket?
        synchronized(lock) { emitted = aggregator.addLevel(dbfs, System.currentTimeMillis()) }
        emitted?.let { enqueuePersist(it) }
    }

    fun onMotion(scorePercent: Int) {
        val emitted: MinuteBucket?
        synchronized(lock) { emitted = aggregator.addMotion(scorePercent, System.currentTimeMillis()) }
        emitted?.let { enqueuePersist(it) }
    }

    /** Flush the in-progress minute and stop the IO thread; call when the session stops. */
    fun shutdown() {
        val emitted: MinuteBucket?
        synchronized(lock) { emitted = aggregator.flush() }
        emitted?.let { enqueuePersist(it) }
        io.shutdown()
        try { io.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }

    private fun enqueuePersist(bucket: MinuteBucket) {
        val classified = bucket.copy(state = SleepState.classify(bucket, awakeDbfs, motionThresholdPercent))
        try {
            io.execute {
                try { store.upsert(classified) } catch (e: Exception) { Log.w(TAG, "night metric persist failed", e) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Recorder already shut down — drop the trailing bucket.
        }
    }

    companion object {
        private const val TAG = "NightRecorder"
        // Defaults tuned for 8 kHz G.711 audio; #285 will expose a sensitivity slider.
        const val DEFAULT_AWAKE_DBFS = -40.0
        const val DEFAULT_MOTION_THRESHOLD = 18
    }
}
