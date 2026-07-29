package com.farmerboy.silageloads

import android.graphics.Color
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where today's loads were dumped, plus the zone and your own position.
 * OpenStreetMap tiles, so there is no API key or Google Maps account.
 *
 * Structured so other drivers' markers can be dropped straight in once
 * there's a backend feeding them: see [drawDrivers].
 */
class MapActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var info: TextView
    private var myLocation: MyLocationNewOverlay? = null

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid needs a user agent or the tile servers reject the requests.
        Configuration.getInstance().load(
            this, PreferenceManager.getDefaultSharedPreferences(this)
        )
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_map)
        map = findViewById(R.id.map)
        info = findViewById(R.id.mapInfo)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )

        findViewById<Button>(R.id.mapCloseBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.mapCenterBtn).setOnClickListener { centerOnMe() }

        draw()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        draw()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    private fun draw() {
        map.overlays.clear()

        val state = LoadStore.read(this)
        val job = LoadStore.activeJob(state)
        val zone = LoadStore.zone(job)

        // dump zone circle
        if (zone != null) {
            val centre = GeoPoint(zone.optDouble("lat"), zone.optDouble("lng"))
            val circle = Polygon(map)
            circle.points = Polygon.pointsAsCircle(centre, zone.optInt("r", 152).toDouble())
            circle.fillPaint.color = Color.argb(48, 255, 176, 32)
            circle.outlinePaint.color = Color.rgb(255, 176, 32)
            circle.outlinePaint.strokeWidth = 4f
            map.overlays.add(circle)

            val pin = Marker(map)
            pin.position = centre
            pin.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            pin.title = "Dump zone"
            map.overlays.add(pin)
        }

        // today's loads, where they were counted
        val today = LoadStore.todayKey()
        val loads = LoadStore.loadsFor(job, today)
        var plotted = 0
        var lastPoint: GeoPoint? = null
        for (i in 0 until loads.length()) {
            val load = loads.getJSONObject(i)
            if (!load.has("lat") || !load.has("lng")) continue
            val point = GeoPoint(load.optDouble("lat"), load.optDouble("lng"))
            val marker = Marker(map)
            marker.position = point
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = "Load #" + (i + 1) +
                (if (load.optBoolean("auto")) " (auto)" else "") +
                " · " + timeFmt.format(Instant.ofEpochMilli(load.optLong("t")).atZone(ZoneId.systemDefault()))
            map.overlays.add(marker)
            lastPoint = point
            plotted++
        }

        drawDrivers()

        // your own position, updating as you move
        val me = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        me.enableMyLocation()
        map.overlays.add(me)
        myLocation = me

        val total = LoadStore.dayCount(job, today)
        info.text = job.optString("name", "Job") + " · " + total + " today · " +
            plotted + " with a location" +
            (if (plotted < total) " (the rest were counted by hand)" else "")

        // frame something sensible: the zone if set, else the newest load
        val focus = when {
            zone != null -> GeoPoint(zone.optDouble("lat"), zone.optDouble("lng"))
            lastPoint != null -> lastPoint
            else -> null
        }
        if (focus != null) {
            map.controller.setZoom(15.0)
            map.controller.setCenter(focus)
        } else {
            map.controller.setZoom(4.0)
            centerOnMe()
        }
        map.invalidate()
    }

    /**
     * Placeholder for the shared-job feature: once a backend is feeding other
     * drivers' positions, add a marker per driver here and call [draw] as
     * updates arrive. Nothing to show while the app is local-only.
     */
    private fun drawDrivers() = Unit

    private fun centerOnMe() {
        val point = myLocation?.myLocation
        if (point != null) {
            map.controller.setZoom(16.0)
            map.controller.animateTo(point)
        }
    }
}
