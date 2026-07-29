package com.farmerboy.silageloads

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Registers the dump zone with Play Services' geofencing, which is handled by the
 * OS: it keeps working with the screen off and the app closed, and costs far less
 * battery than us polling GPS ourselves.
 */
object GeofenceManager {

    const val FENCE_ID = "dump_zone"

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx.applicationContext, GeofenceReceiver::class.java)
            .setAction(GeofenceReceiver.ACTION_FENCE)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(ctx.applicationContext, 0, intent, flags)
    }

    fun hasForegroundLocation(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Background access is a separate, second grant from Android 10 onwards. */
    fun hasBackgroundLocation(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasForegroundLocation(ctx)
        return hasForegroundLocation(ctx) && ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Re-register from whatever is currently stored. Safe to call any time —
     * on zone change, on toggling auto, at boot, and on app start.
     */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        val state = LoadStore.read(app)
        val zone = LoadStore.zone(LoadStore.activeJob(state))
        val wanted = zone != null && zone.optBoolean("auto")
        remove(app)
        if (!wanted || !hasBackgroundLocation(app)) return

        val fence = Geofence.Builder()
            .setRequestId(FENCE_ID)
            .setCircularRegion(
                zone!!.optDouble("lat"),
                zone.optDouble("lng"),
                zone.optInt("r", 152).toFloat()
            )
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            // ENTER counts the moment you cross in; DWELL is a safety net if that
            // broadcast is ever missed. The receiver's cooldown collapses the pair
            // so one arrival can only ever count once.
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .setLoiteringDelay(30_000)
            .setNotificationResponsiveness(30_000)
            .build()

        val request = GeofencingRequest.Builder()
            // Do NOT use INITIAL_TRIGGER_ENTER: sitting in the zone when you set it
            // up must not count a load. Only a real entry should.
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()

        try {
            LocationServices.getGeofencingClient(app).addGeofences(request, pendingIntent(app))
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; nothing to do.
        }
    }

    fun remove(ctx: Context) {
        try {
            LocationServices.getGeofencingClient(ctx.applicationContext)
                .removeGeofences(pendingIntent(ctx))
        } catch (_: Exception) {
        }
    }
}
