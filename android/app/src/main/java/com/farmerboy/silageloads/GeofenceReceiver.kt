package com.farmerboy.silageloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Fires when the truck drives into the dump zone — with the screen off and the
 * app closed. Counts the load, refreshes the widget, and posts a quiet
 * notification so there is visible proof it happened.
 */
class GeofenceReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FENCE = "com.farmerboy.silageloads.GEOFENCE"
        private const val CHANNEL = "auto_loads"
        /** Ignore a second entry within this window (GPS bounce at the edge). */
        private const val COOLDOWN_MS = 60_000L
        private const val PREFS = "silage_loads_fence"
        private const val KEY_LAST = "last_auto_ms"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FENCE) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val transition = event.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER &&
            transition != Geofence.GEOFENCE_TRANSITION_DWELL
        ) return

        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST, 0L) < COOLDOWN_MS) return
        prefs.edit().putLong(KEY_LAST, now).apply()

        val where = event.triggeringLocation
        val count = LoadStore.addLoad(
            app,
            auto = true,
            lat = where?.latitude,
            lng = where?.longitude
        )

        LoadWidget.refresh(app)
        notify(app, count)
    }

    private fun notify(ctx: Context, count: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, "Auto-counted loads", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Posted when a load is counted by the dump zone" }
            nm.createNotificationChannel(channel)
        }
        val tap = LoadWidget.openAppIntent(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_load)
            .setContentTitle("Load counted")
            .setContentText("$count today · counted at the dump zone")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(1001, n)
        } catch (_: SecurityException) {
            // Notification permission not granted; the load is still counted.
        }
    }
}
