package com.farmerboy.silageloads

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
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

    private const val PREFS = "silage_loads_fence"
    private const val KEY_STATUS = "fence_status"

    /** What the last registration attempt did, for the UI to show. */
    fun status(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "") ?: ""

    /** When the geofence last counted a load, or 0. Written by GeofenceReceiver. */
    fun lastAutoCount(ctx: Context): Long =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("last_auto_ms", 0L)

    private fun setStatus(ctx: Context, s: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATUS, s).apply()
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

        if (!wanted) {
            // Only remove when we actually want it off. Removing right before an
            // add is a race: both calls are async, and a late-landing removal
            // silently wipes the geofence we just registered.
            remove(app)
            setStatus(app, if (zone == null) "no zone set" else "auto is off")
            return
        }
        if (!hasForegroundLocation(app)) {
            setStatus(app, "BLOCKED: location permission not granted")
            return
        }
        if (!hasBackgroundLocation(app)) {
            setStatus(app, "BLOCKED: needs \"Allow all the time\"")
            return
        }

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
            // addGeofences replaces any existing fence with the same request id,
            // so there is no need to remove first.
            LocationServices.getGeofencingClient(app)
                .addGeofences(request, pendingIntent(app))
                .addOnSuccessListener { setStatus(app, "watching for arrivals") }
                .addOnFailureListener { e -> setStatus(app, "FAILED: " + describe(e)) }
        } catch (e: SecurityException) {
            setStatus(app, "BLOCKED: permission revoked")
        }
    }

    /** Turn Play Services' geofence errors into something readable on the phone. */
    private fun describe(e: Exception): String {
        val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
        return when (code) {
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
                "location services off, or Google location accuracy is disabled"
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES -> "too many geofences"
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS -> "too many pending intents"
            GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION ->
                "insufficient location permission"
            else -> e.message ?: "unknown error"
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
