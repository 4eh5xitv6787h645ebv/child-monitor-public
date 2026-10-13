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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Trampoline service started by BootReceiver. Post-Android-10 we can't directly start an
 * Activity from a BroadcastReceiver background context, so we go through a short-lived
 * foreground service that immediately launches the requested role's activity.
 */
class BootService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val role = intent?.getStringExtra(EXTRA_ROLE) ?: ROLE_CHILD
        Log.i(TAG, "BootService trampoline starting for role=$role")

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.listening_notification)
            .setContentTitle("Child Monitor")
            .setContentText("Resuming after device boot…")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        when (role) {
            ROLE_PARENT -> launchParent(intent)
            else -> launchChild()
        }

        stopSelf()
        return START_NOT_STICKY
    }

    private fun launchChild() {
        val launchIntent = Intent(this, MonitorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(launchIntent)
    }

    private fun launchParent(triggerIntent: Intent?) {
        // #402 Kids: prefer resuming a paired kid BY deviceId (default kid → most-recent) so boot
        // auto-start uses the permanent-link path (token/pin reuse, kid name, fast+strict reconnect).
        val targetKid = QuickReconnectTarget.resolve(
            PairedChildrenStore.getDefaultKidDeviceId(this),
            PairedChildrenStore.list(this)
        )
        if (targetKid != null) {
            Log.i(TAG, "launchParent: resuming kid=${targetKid.deviceId} (autoReconnect=true)")
            startActivity(KidConnect.buildIntent(this, targetKid, Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        // Legacy fallback: no paired child → use the receiver's saved "ip:port".
        val endpoint = NetworkEndpoint.parse(
            triggerIntent?.getStringExtra(EXTRA_PARENT_IP_PORT), defaultPort = 10000
        )
        if (endpoint == null) {
            Log.w(TAG, "launchParent: no paired kid and no usable address in extras, falling back to child role")
            launchChild()
            return
        }
        val address = endpoint.host
        val port = endpoint.port
        val launchIntent = Intent(this, ListenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val b = Bundle()
            b.putString("address", address)
            b.putInt("port", port)
            b.putString("name", address)
            b.putBoolean("autoReconnect", true)
            putExtras(b)
        }
        Log.i(TAG, "launchParent: resuming ip=$address:$port (autoReconnect=true)")
        startActivity(launchIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Boot Service", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "ChildMonitor"
        private const val CHANNEL_ID = "BootServiceChannel"
        private const val NOTIFICATION_ID = 9999
        const val EXTRA_ROLE = "extra_role"
        const val EXTRA_PARENT_IP_PORT = "extra_parent_ip_port"
        const val ROLE_CHILD = "child"
        const val ROLE_PARENT = "parent"
    }
}
