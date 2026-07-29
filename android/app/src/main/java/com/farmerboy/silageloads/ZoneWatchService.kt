package com.farmerboy.silageloads

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Watches the dump zone while AUTO is on.
 *
 * The OS geofence alone proved unreliable — phone makers put background apps to
 * sleep, and when the system drops a geofence nothing says so and nothing puts
 * it back, which is why counting stopped after working once. A foreground
 * service with its own notification is the thing Android will not quietly kill.
 *
 * Battery is managed by asking for fixes at a rate that matches distance: far
 * from the pit it idles at a couple of minutes between coarse fixes, and only
 * tightens up as you get close.
 */
class ZoneWatchService : Service() {

    companion object {
        private const val CHANNEL = "zone_watch"
        private const val NOTIF_ID = 2001
        private const val NEAR_M = 1200.0
        private const val MAX_SPEED_MPS = 25.0

        fun start(ctx: Context) {
            val app = ctx.applicationContext
            val zone = LoadStore.zone(LoadStore.activeJob(LoadStore.read(app)))
            if (zone == null || !zone.optBoolean("auto")) return
            if (!GeofenceManager.hasForegroundLocation(app)) return
            try {
                ContextCompat.startForegroundService(app, Intent(app, ZoneWatchService::class.java))
            } catch (_: Exception) {
                // e.g. background-start restrictions; the geofence still covers us
            }
        }

        fun stop(ctx: Context) {
            val app = ctx.applicationContext
            try {
                app.stopService(Intent(app, ZoneWatchService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var currentInterval = 0L
    private var lastText = ""

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely("Watching for the dump zone")
        requestUpdates(30_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert the notification in case we were restarted by the system.
        startForegroundSafely(lastText.ifEmpty { "Watching for the dump zone" })
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            client.removeLocationUpdates(callback)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(intervalMs: Long) {
        if (intervalMs == currentInterval) return
        currentInterval = intervalMs
        val priority =
            if (intervalMs <= 30_000L) Priority.PRIORITY_HIGH_ACCURACY
            else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            client.removeLocationUpdates(callback)
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun onFix(location: Location) {
        val zone = LoadStore.zone(LoadStore.activeJob(LoadStore.read(this)))
        if (zone == null || !zone.optBoolean("auto")) {
            stopSelf()
            return
        }
        val radius = zone.optInt("r", 152).toDouble()
        val distance = AutoCounter.metresBetween(
            location.latitude, location.longitude, zone.optDouble("lat"), zone.optDouble("lng")
        )

        if (distance <= radius) {
            // Only an arrival counts: you must have genuinely been away first,
            // which stops GPS wobble at the edge from counting twice.
            if (AutoCounter.isArmed(this)) {
                AutoCounter.countArrival(this, location.latitude, location.longitude)
                note("In the zone · counted")
            } else {
                note("In the zone")
            }
        } else {
            if (distance > radius + max(60.0, radius * 0.5)) AutoCounter.setArmed(this, true)
            note(
                describe(distance) + " out · " +
                    (if (AutoCounter.isArmed(this)) "ready" else "leave a bit further to arm")
            )
        }

        // cheapest rate that still cannot miss an arrival
        val outside = distance - radius
        val next = if (outside <= NEAR_M) 30_000L
        else (outside / MAX_SPEED_MPS * 0.4 * 1000).toLong().coerceIn(30_000L, 150_000L)
        requestUpdates(next)
    }

    private fun describe(m: Double): String =
        if (m < 305) "${(m * 3.28084).roundToInt()} ft" else String.format("%.1f mi", m / 1609.34)

    private fun note(text: String) {
        if (text == lastText) return
        lastText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Dump zone watch", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Shown while the app is watching for arrivals" }
            )
        }
        val today = LoadStore.dayCount(LoadStore.activeJob(LoadStore.read(this)), LoadStore.todayKey())
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_load)
            .setContentTitle("Auto counting · $today today")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(LoadWidget.openAppIntent(this))
            .build()
    }

    private fun startForegroundSafely(text: String) {
        lastText = text
        val n = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (_: Exception) {
            stopSelf()
        }
    }
}
