package com.farmerboy.silageloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The one place a load gets counted automatically.
 *
 * Two things can trigger it — the OS geofence and the foreground watcher — so
 * the cooldown and the "armed" state live here, shared between them. That way
 * one arrival counts once no matter which path noticed it first.
 */
object AutoCounter {

    private const val PREFS = "silage_loads_fence"
    private const val KEY_LAST = "last_auto_ms"
    private const val KEY_ARMED = "armed"
    private const val CHANNEL = "auto_loads"
    private const val COOLDOWN_MS = 60_000L

    fun metresBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val rad = Math.PI / 180.0
        val dLat = (lat2 - lat1) * rad
        val dLng = (lng2 - lng1) * rad
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * rad) * cos(lat2 * rad) * sin(dLng / 2) * sin(dLng / 2)
        return 6371000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastCount(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST, 0L)

    fun isArmed(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ARMED, false)

    fun setArmed(ctx: Context, armed: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ARMED, armed).apply()
    }

    /**
     * Count an arrival unless one was already counted moments ago.
     * Returns true if a load was actually added.
     */
    @Synchronized
    fun countArrival(ctx: Context, lat: Double?, lng: Double?): Boolean {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        if (now - lastCount(app) < COOLDOWN_MS) return false
        prefs(app).edit().putLong(KEY_LAST, now).putBoolean(KEY_ARMED, false).apply()

        val count = LoadStore.addLoad(app, auto = true, lat = lat, lng = lng)
        Sync.pushLoadForActive(app, auto = true, lat = lat, lng = lng)
        LoadWidget.refresh(app)
        notify(app, count)
        return true
    }

    private fun notify(ctx: Context, count: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Auto-counted loads", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Posted when a load is counted at the dump zone" }
            )
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_load)
            .setContentTitle("Load counted")
            .setContentText("$count today · counted at the dump zone")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(LoadWidget.openAppIntent(ctx))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(1001, n)
        } catch (_: SecurityException) {
            // Notifications not granted; the load is still counted.
        }
    }
}
