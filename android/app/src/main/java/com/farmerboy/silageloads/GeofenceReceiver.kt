package com.farmerboy.silageloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * The OS geofence path. Kept as a backup to [ZoneWatchService]: it costs nothing
 * when idle and can still catch an arrival if the service was killed. Both go
 * through [AutoCounter], whose cooldown means one arrival counts once.
 */
class GeofenceReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FENCE = "com.farmerboy.silageloads.GEOFENCE"
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
        val where = event.triggeringLocation
        AutoCounter.countArrival(app, where?.latitude, where?.longitude)

        // If the watcher isn't running (killed, or rebooted), this is a good
        // moment to bring it back.
        ZoneWatchService.start(app)
    }
}
