package de.rochefort.childmonitor.util

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [#230] On API 34+, startForeground() with a FGS type whose runtime permission is not
 * granted throws SecurityException. The promoted type must therefore contain only the
 * types actually granted.
 */
class ForegroundServiceTypePolicyTest {

    private val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val cam = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    private val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

    @Test
    fun belowR_returnsZero_typedFgsNotRequired() {
        assertEquals(0, ForegroundServiceTypePolicy.typeFor(29, hasMic = true, hasCamera = true))
    }

    @Test
    fun r_micAndCamera_bothBits() {
        assertEquals(mic or cam, ForegroundServiceTypePolicy.typeFor(30, hasMic = true, hasCamera = true))
    }

    @Test
    fun micOnly_whenCameraDenied() {
        assertEquals(mic, ForegroundServiceTypePolicy.typeFor(34, hasMic = true, hasCamera = false))
    }

    @Test
    fun cameraOnly_whenMicDenied() {
        assertEquals(cam, ForegroundServiceTypePolicy.typeFor(34, hasMic = false, hasCamera = true))
    }

    @Test
    fun none_whenBothDenied() {
        assertEquals(0, ForegroundServiceTypePolicy.typeFor(34, hasMic = false, hasCamera = false))
    }

    // #327: MEDIA_PLAYBACK is OR'd in while audio is playing, independently of mic/camera.

    @Test
    fun playingAudio_addsMediaPlaybackBit() {
        assertEquals(
            mic or cam or media,
            ForegroundServiceTypePolicy.typeFor(34, hasMic = true, hasCamera = true, isPlayingAudio = true)
        )
    }

    @Test
    fun playingAudio_withMicRevoked_stillKeepsMediaPlayback() {
        // The exact edge #327 targets: mic/camera types are absent but audio is playing, so the FGS
        // still advertises MEDIA_PLAYBACK rather than an empty (kill-prone) type set.
        assertEquals(media, ForegroundServiceTypePolicy.typeFor(34, hasMic = false, hasCamera = false, isPlayingAudio = true))
    }

    @Test
    fun notPlayingAudio_defaultsToNoMediaPlayback() {
        assertEquals(mic, ForegroundServiceTypePolicy.typeFor(34, hasMic = true, hasCamera = false, isPlayingAudio = false))
        // default arg is false → unchanged from the 3-arg behavior
        assertEquals(mic, ForegroundServiceTypePolicy.typeFor(34, hasMic = true, hasCamera = false))
    }

    @Test
    fun belowR_returnsZero_evenWhenPlayingAudio() {
        assertEquals(0, ForegroundServiceTypePolicy.typeFor(29, hasMic = true, hasCamera = true, isPlayingAudio = true))
    }
}
