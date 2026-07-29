package com.farmerboy.silageloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Geofences are dropped on reboot, so put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            GeofenceManager.sync(context)
            ZoneWatchService.start(context)
            LoadWidget.refresh(context)
        }
    }
}
