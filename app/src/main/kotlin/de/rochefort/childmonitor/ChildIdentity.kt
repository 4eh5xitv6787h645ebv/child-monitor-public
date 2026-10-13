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
import android.os.Build
import java.util.UUID

/**
 * Stable identity for the child device — generated once on first run and persisted, so
 * the parent device can find this child by id even when the IP changes (new WiFi, DHCP
 * lease rotation, hotspot, etc.). The id is short (8 hex chars) for nicer QR codes;
 * collision risk is acceptable for a local-network monitor.
 */
object ChildIdentity {
    private const val PREFS = "ChildIdentity"
    private const val KEY_ID = "childDeviceId"
    private const val KEY_NAME = "childDeviceName"

    /** Stable per-install UUID-derived id. Lazily generated and cached in shared prefs. */
    fun getId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }
        // 8 hex chars = ~32 bits — collision risk is roughly 1 in 4 billion for a household
        // with even a single other Child Monitor install. Plenty for a local-network scenario.
        val fresh = UUID.randomUUID().toString().take(8)
        prefs.edit().putString(KEY_ID, fresh).apply()
        return fresh
    }

    /** Friendly name shown to the parent. Defaults to the device model; user-editable. */
    fun getName(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_NAME, null)?.let { if (it.isNotBlank()) return it }
        return Build.MODEL ?: "Child device"
    }

    /**
     * The user-set custom name, or null if the device is still on the default
     * (i.e. [getName] would fall back to [Build.MODEL]). Used by the mDNS TXT
     * record so the device model is never advertised to passive observers (#209).
     */
    fun getCustomName(context: Context): String? {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setName(context: Context, name: String) {
        val sanitized = name.trim().take(40)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAME, sanitized).apply()
    }

    /**
     * Opaque name to publish over the mDNS NSD service. #190-HIGH: previously
     * we used "ChildMonitor on $Build.MODEL" verbatim, which let any passive
     * mDNS observer on the LAN identify the device model ("Pixel 8 Pro",
     * "SM-A556E", etc.). Now we publish "ChildMonitor [id]" with just the
     * 8-hex stable id — enough for parents that already paired (they match on
     * id from the QR), opaque to everyone else. #209: the TXT record carries a
     * human name only when the user set a custom one (see [getCustomName]) — the
     * [Build.MODEL] default is never advertised; the NSD service name itself
     * stays opaque.
     */
    fun getPublicMdnsName(context: Context): String = "ChildMonitor [${getId(context)}]"
}
