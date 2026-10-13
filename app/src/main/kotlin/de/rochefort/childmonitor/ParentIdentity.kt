package de.rochefort.childmonitor

import android.content.Context
import java.util.UUID

/** Independent parent identity: a child ID in a QR identifies the destination, never the phone. */
object ParentIdentity {
    @Synchronized
    fun getId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("ParentIdentity", Context.MODE_PRIVATE)
        prefs.getString("deviceId", null)?.let { return it }
        val id = "parent-${UUID.randomUUID()}"
        check(prefs.edit().putString("deviceId", id).commit()) { "Could not persist parent identity" }
        return id
    }
}
