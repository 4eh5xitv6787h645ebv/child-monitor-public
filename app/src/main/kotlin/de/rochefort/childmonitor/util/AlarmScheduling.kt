/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.rochefort.childmonitor.util

import android.os.Build

/**
 * Which AlarmManager setter to use for a wake-up alarm.
 *
 * [#229] `setExactAndAllowWhileIdle` and `setAndAllowWhileIdle` were both introduced in
 * API 23 (M). On the supported API 21–22 (minSdk 21) invoking them throws
 * `NoSuchMethodError`, so below M we fall back to `setExact` (API 19+). Doze did not
 * exist before M, so an exact alarm behaves equivalently to "exact and allow while idle".
 */
enum class AlarmMode {
    /** API < 23: `AlarmManager.setExact` (no while-idle method exists yet; no Doze pre-M). */
    EXACT_LEGACY,

    /** API >= 23 with exact alarms permitted: `setExactAndAllowWhileIdle`. */
    EXACT_WHILE_IDLE,

    /** API >= 23 with exact alarms not permitted (API 31+ policy): `setAndAllowWhileIdle`. */
    INEXACT_WHILE_IDLE,
}

object AlarmScheduling {

    /**
     * Pick the alarm mode for the running platform.
     *
     * @param sdkInt running [Build.VERSION.SDK_INT].
     * @param exactAllowed whether exact alarms may currently be used. Callers MUST compute
     *   this with the short-circuit `sdkInt < S || alarmManager.canScheduleExactAlarms()`
     *   so that `canScheduleExactAlarms()` (API 31+) is never invoked below API 31. The
     *   value is only consulted when [sdkInt] >= 23.
     */
    fun chooseMode(sdkInt: Int, exactAllowed: Boolean): AlarmMode = when {
        sdkInt < Build.VERSION_CODES.M -> AlarmMode.EXACT_LEGACY
        exactAllowed -> AlarmMode.EXACT_WHILE_IDLE
        else -> AlarmMode.INEXACT_WHILE_IDLE
    }
}
