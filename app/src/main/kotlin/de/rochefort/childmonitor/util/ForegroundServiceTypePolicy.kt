/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.rochefort.childmonitor.util

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Computes the foreground-service type bitmask for the child [MonitorService].
 *
 * [#230] On API 34+ `Service.startForeground()` with `FOREGROUND_SERVICE_TYPE_CAMERA`
 * or `_MICROPHONE` throws `SecurityException` if the matching runtime permission is not
 * granted. Because the service runs with `START_REDELIVER_INTENT`, a mid-session revoke
 * would otherwise crash-loop. So we promote with only the types whose permission is held;
 * audio capture and camera streaming each degrade gracefully when their permission is
 * missing.
 *
 * Typed foreground services are only requested at API 30 (R) and above here, matching the
 * prior behaviour; below that the type is 0.
 */
object ForegroundServiceTypePolicy {

    /**
     * @param isPlayingAudio true while the child is actively playing audio (talkback from the parent
     *   or the #311 soother). [#327] On API 34+ the platform can throttle/kill an FGS whose declared
     *   work isn't reflected in its active type set, and the MICROPHONE type may not be held (mic
     *   revoked, or a future audio-only-without-mic mode) — so while audio plays we OR in
     *   MEDIA_PLAYBACK. Unlike MICROPHONE/CAMERA it carries no runtime-permission gate (only the
     *   normal FOREGROUND_SERVICE_MEDIA_PLAYBACK permission, always granted), so adding it can never
     *   throw SecurityException at startForeground(). The MonitorService manifest declares
     *   `camera|microphone|mediaPlayback`, so this bit is always within the declared set.
     */
    fun typeFor(sdkInt: Int, hasMic: Boolean, hasCamera: Boolean, isPlayingAudio: Boolean = false): Int {
        if (sdkInt < Build.VERSION_CODES.R) return 0
        var type = 0
        if (hasMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (hasCamera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (isPlayingAudio) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        return type
    }
}
