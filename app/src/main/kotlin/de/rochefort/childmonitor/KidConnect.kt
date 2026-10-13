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
package de.rochefort.childmonitor

import android.content.Context
import android.content.Intent

/**
 * Single source for the "connect/reconnect to this kid" [ListenActivity] intent — the permanent-link
 * path (#402): pass the stable deviceId (token/pin reuse + fast+strict reconnect), the child-advertised
 * name as `"name"` (persisted as [PairedChild.name]), and the parent label separately as
 * [ListenActivity.EXTRA_KID_DISPLAY_NAME] (display-only — never persisted). Used by KidsActivity,
 * StartActivity Quick-Reconnect, and BootService so the extras contract can't drift between them.
 *
 * [flags] differ by caller: an Activity launch uses CLEAR_TOP|SINGLE_TOP; a Service (boot) uses NEW_TASK.
 */
object KidConnect {

    fun buildIntent(context: Context, kid: PairedChild, flags: Int): Intent =
        Intent(context, ListenActivity::class.java).apply {
            this.flags = flags
            putExtra("address", kid.lastKnownIp)
            putExtra("port", kid.lastKnownPort)
            putExtra("name", kid.name)
            kid.userName?.takeIf { it.isNotBlank() }?.let { putExtra(ListenActivity.EXTRA_KID_DISPLAY_NAME, it) }
            putExtra("deviceId", kid.deviceId)
            putExtra("autoReconnect", true)
        }
}
