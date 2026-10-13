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
package de.rochefort.childmonitor.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import de.rochefort.childmonitor.R
import java.util.Random

/**
 * #311: plays continuous soothing noise on the child's own speaker (near the baby), parent
 * controlled. A writer thread streams procedurally-generated PCM into a MODE_STREAM AudioTrack;
 * bundled recordings use MediaPlayer. Loudness is per-player gain, so the system music volume is
 * left untouched (consistent with the talkback #194-4 decision).
 *
 * The child mic still streams while this plays. MonitorService attaches Android's acoustic echo
 * canceller to that capture session where the device supports it, which reduces the child's own
 * speaker in the parent feed without filtering ordinary room sounds. Some physical bleed remains
 * possible, especially at high speaker volume.
 *
 * Lifecycle assumption: the soother runs only while child mode is active, where MonitorService
 * already holds the MICROPHONE foreground-service type, so the player does not manage the FGS
 * type itself (declaring MEDIA_PLAYBACK for audio playback is a tracked follow-up).
 */
class SootherPlayer(private val context: Context) {

    private val lock = Any()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var mediaPlayer: MediaPlayer? = null
    private var writerThread: Thread? = null
    @Volatile private var currentSound: SoothingSound = SoothingSound.WHITE
    @Volatile private var currentVolume: Float = SootherPolicy.DEFAULT_VOLUME
    @Volatile private var playing = false

    /**
     * #327/#328: invoked when the writer thread dies on its OWN (an AudioTrack write error /
     * exception) rather than via [stop]. The player has already torn down its track and set
     * playing=false by the time this fires. Called ON the writer thread, so the callback MUST NOT
     * call [stop] synchronously (it would self-join) — marshal to another thread/handler. Lets the
     * owner clear the FGS MEDIA_PLAYBACK type and any sleep-timer that's now moot.
     */
    @Volatile var onUnexpectedStop: (() -> Unit)? = null

    fun isPlaying(): Boolean = playing
    fun sound(): SoothingSound = currentSound
    fun volume(): Float = currentVolume

    /**
     * Start playing [sound] at linear [volume] (0..1). If already playing, switches the sound
     * (read live by the writer) and volume without restarting the track. Returns true if the
     * soother is playing after the call, false if the AudioTrack could not be created/started
     * (so the caller can report an honest failure rather than a silent no-op).
     */
    fun start(sound: SoothingSound, volume: Float): Boolean {
        // A generated stream can switch live to another generated stream, and the bundled player
        // can update its volume live. Crossing between engines needs an orderly stop/release first.
        val crossingEngines = synchronized(lock) {
            playing && ((mediaPlayer != null) != sound.bundledRecording)
        }
        if (crossingEngines) stop()
        return if (sound.bundledRecording) startRecording(sound, volume)
        else startGenerated(sound, volume)
    }

    private fun startGenerated(sound: SoothingSound, volume: Float): Boolean = synchronized(lock) {
        val vol = SootherPolicy.clampVolume(volume)
        currentVolume = vol
        val existing = track
        if (existing != null && playing) {
            // Codex: apply the track volume BEFORE publishing currentSound. The writer reads
            // currentSound (@Volatile) without the lock; publishing it last means a reader that sees
            // the new sound is guaranteed (volatile happens-before) to also see this setVolume, so it
            // can never render a chunk of the new sound at the old volume.
            existing.setVolume(vol)
            currentSound = sound // live switch: writer picks up the new sound on its next chunk
            Log.i(TAG, "Soother switched: $sound vol=$vol")
            return@synchronized true
        }
        currentSound = sound
        val newTrack = buildTrack() ?: return@synchronized false
        newTrack.setVolume(vol)
        try {
            newTrack.play()
        } catch (e: Exception) {
            Log.e(TAG, "Soother AudioTrack.play failed", e)
            newTrack.release()
            return@synchronized false
        }
        track = newTrack
        playing = true
        val t = Thread({ writeLoop(newTrack) }, "soother-writer")
        writerThread = t
        t.start()
        Log.i(TAG, "Soother started: $sound vol=$vol")
        true
    }

    private fun startRecording(sound: SoothingSound, volume: Float): Boolean = synchronized(lock) {
        val vol = SootherPolicy.clampVolume(volume)
        currentVolume = vol
        val existing = mediaPlayer
        if (existing != null && playing) {
            existing.setVolume(vol, vol)
            currentSound = sound
            Log.i(TAG, "Soother recording volume updated: $sound vol=$vol")
            return@synchronized true
        }

        currentSound = sound
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val player = try {
            MediaPlayer.create(
                context,
                R.raw.brahms_lullaby_elsie_baker,
                attrs,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create bundled soother player", e)
            null
        } ?: return@synchronized false

        player.isLooping = true
        player.setVolume(vol, vol)
        player.setOnErrorListener { failedPlayer, what, extra ->
            handleRecordingError(failedPlayer, what, extra)
            true
        }
        mediaPlayer = player
        playing = true
        try {
            player.start()
        } catch (e: Exception) {
            playing = false
            mediaPlayer = null
            try { player.release() } catch (_: Exception) {}
            Log.e(TAG, "Bundled soother MediaPlayer.start failed", e)
            return@synchronized false
        }
        Log.i(TAG, "Soother started bundled recording: $sound vol=$vol")
        true
    }

    fun setVolume(volume: Float) {
        val vol = SootherPolicy.clampVolume(volume)
        synchronized(lock) {
            currentVolume = vol
            track?.setVolume(vol)
            mediaPlayer?.setVolume(vol, vol)
        }
    }

    fun stop() {
        val t: AudioTrack?
        val m: MediaPlayer?
        val w: Thread?
        synchronized(lock) {
            if (!playing && track == null && mediaPlayer == null) return
            playing = false
            t = track
            m = mediaPlayer
            w = writerThread
            track = null
            mediaPlayer = null
            writerThread = null
        }
        // Tear down outside the lock so we don't hold it across the writer join.
        // Order matters (the #228 use-after-release lesson): pause() FIRST so a writer parked in
        // a blocking write() returns immediately, observes playing=false, and exits; join it; only
        // THEN flush/stop/release, guaranteeing the track is never released under an in-flight write.
        try { t?.pause() } catch (_: Exception) {}
        w?.interrupt()
        try {
            w?.join(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (t != null) {
            try { t.flush() } catch (_: Exception) {}
            try { t.stop() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
        }
        if (m != null) {
            try { m.stop() } catch (_: Exception) {}
            try { m.release() } catch (_: Exception) {}
        }
        Log.i(TAG, "Soother stopped")
    }

    private fun handleRecordingError(failedPlayer: MediaPlayer, what: Int, extra: Int) {
        var died = false
        synchronized(lock) {
            if (playing && mediaPlayer === failedPlayer) {
                playing = false
                mediaPlayer = null
                died = true
            }
        }
        if (!died) return
        try { failedPlayer.release() } catch (_: Exception) {}
        Log.w(TAG, "Bundled soother stopped unexpectedly (what=$what extra=$extra)")
        try { onUnexpectedStop?.invoke() } catch (e: Exception) {
            Log.w(TAG, "onUnexpectedStop callback failed", e)
        }
    }

    private fun writeLoop(t: AudioTrack) {
        val filter = NoiseFilter()
        val rnd = Random()
        val chunk = ShortArray(CHUNK_SAMPLES)
        var abnormal = false
        // #328: track the live sound so a remote sound-switch rebuilds the right generator. Noise →
        // NoiseGenerator (full scale; AudioTrack.setVolume applies loudness); melody → MelodySynth.
        var activeSound = currentSound
        var melody: MelodySynth? = activeSound.lullaby?.let { MelodySynth(it, SAMPLE_RATE) }
        // Codex: a generator switch is a hard chunk-boundary cut between two unrelated waveforms,
        // which clicks. Ramp the new generator in from silence over ~6ms after any switch so the new
        // chunk starts at 0 instead of an abrupt step. (The melody side is already enveloped; this
        // also softens the abrupt onset of noise.)
        val switchFadeSamples = (SAMPLE_RATE * 0.006).toInt().coerceAtLeast(1)
        var switchFadeRemaining = 0
        try {
            while (playing && !Thread.currentThread().isInterrupted) {
                val s = currentSound
                if (s != activeSound) {
                    activeSound = s
                    // Rebuild on any switch (noise→melody, melody→melody at pos 0, melody→noise=null).
                    melody = s.lullaby?.let { MelodySynth(it, SAMPLE_RATE) }
                    switchFadeRemaining = switchFadeSamples
                }
                val m = melody
                if (m != null) m.fill(chunk, 1f) else NoiseGenerator.fill(chunk, s, 1f, rnd, filter)
                if (switchFadeRemaining > 0) {
                    var j = 0
                    while (j < chunk.size && switchFadeRemaining > 0) {
                        val g = (switchFadeSamples - switchFadeRemaining).toFloat() / switchFadeSamples
                        chunk[j] = (chunk[j] * g).toInt().toShort()
                        switchFadeRemaining--
                        j++
                    }
                }
                // The 3-argument overload is blocking for MODE_STREAM and exists on API 21;
                // WRITE_BLOCKING's 4-argument overload was added only in API 23.
                val written = t.write(chunk, 0, chunk.size)
                if (written < 0) {
                    Log.w(TAG, "Soother AudioTrack.write error: $written")
                    abnormal = true
                    break
                }
                if (written == 0) {
                    // Non-error short write (e.g. just paused / transient mixer state): back off
                    // so we never hot-spin a CPU core on a crib device.
                    if (!playing || Thread.currentThread().isInterrupted) break
                    try {
                        Thread.sleep(5)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Soother writer ended", e)
            abnormal = true
        }
        // #327/#328: if the writer died on its own (AudioTrack error/exception) instead of via stop()
        // — which sets playing=false and nulls track first — then `playing` is still true and `track`
        // is still this thread's track. Previously nothing reset that, so isPlaying() lied forever, a
        // later start() tried to reuse a dead track, and the FGS MEDIA_PLAYBACK bit/sleep-timer leaked.
        // Tear down OUR OWN track (only if still current) under the lock, then notify. Releasing t here
        // is safe: we atomically null `track` so stop() will early-return and never touch t.
        if (abnormal) {
            var died = false
            synchronized(lock) {
                if (playing && track === t) {
                    playing = false
                    track = null
                    writerThread = null
                    died = true
                }
            }
            if (died) {
                try { t.flush() } catch (_: Exception) {}
                try { t.stop() } catch (_: Exception) {}
                try { t.release() } catch (_: Exception) {}
                Log.w(TAG, "Soother stopped unexpectedly (writer died)")
                try { onUnexpectedStop?.invoke() } catch (e: Exception) {
                    Log.w(TAG, "onUnexpectedStop callback failed", e)
                }
            }
        }
    }

    private fun buildTrack(): AudioTrack? {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            // getMinBufferSize can return ERROR(-1)/ERROR_BAD_VALUE(-2) if the engine isn't ready;
            // fall back to ~45ms (8000 shorts @44.1kHz mono) and double it for stream headroom.
            val effMin = if (minBuf > 0) minBuf else 8000
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val fmt = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            AudioTrack(attrs, fmt, effMin * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build soother AudioTrack", e)
            null
        }
    }

    companion object {
        private const val TAG = "SootherPlayer"
        private const val SAMPLE_RATE = 44100
        private const val CHUNK_SAMPLES = 4096
    }
}
