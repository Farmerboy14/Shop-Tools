package com.farmerboy.silageloads

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Per-state offline maps.
 *
 * Mapsforge publishes one compact vector map file per US state, built for
 * exactly this: download the states you work in and the map renders with no
 * signal, at full zoom, without pulling millions of individual tiles.
 */
object OfflineMaps {

    data class State(val label: String, val file: String)

    private const val BASE = "https://download.mapsforge.org/maps/v5/north-america/us/"
    private const val PREFS = "offline_maps"

    val STATES = listOf(
        State("Alabama", "alabama"), State("Alaska", "alaska"),
        State("Arizona", "arizona"), State("Arkansas", "arkansas"),
        State("California", "california"), State("Colorado", "colorado"),
        State("Connecticut", "connecticut"), State("Delaware", "delaware"),
        State("Florida", "florida"), State("Georgia", "georgia"),
        State("Hawaii", "hawaii"), State("Idaho", "idaho"),
        State("Illinois", "illinois"), State("Indiana", "indiana"),
        State("Iowa", "iowa"), State("Kansas", "kansas"),
        State("Kentucky", "kentucky"), State("Louisiana", "louisiana"),
        State("Maine", "maine"), State("Maryland", "maryland"),
        State("Massachusetts", "massachusetts"), State("Michigan", "michigan"),
        State("Minnesota", "minnesota"), State("Mississippi", "mississippi"),
        State("Missouri", "missouri"), State("Montana", "montana"),
        State("Nebraska", "nebraska"), State("Nevada", "nevada"),
        State("New Hampshire", "new-hampshire"), State("New Jersey", "new-jersey"),
        State("New Mexico", "new-mexico"), State("New York", "new-york"),
        State("North Carolina", "north-carolina"), State("North Dakota", "north-dakota"),
        State("Ohio", "ohio"), State("Oklahoma", "oklahoma"),
        State("Oregon", "oregon"), State("Pennsylvania", "pennsylvania"),
        State("Rhode Island", "rhode-island"), State("South Carolina", "south-carolina"),
        State("South Dakota", "south-dakota"), State("Tennessee", "tennessee"),
        State("Texas", "texas"), State("Utah", "utah"),
        State("Vermont", "vermont"), State("Virginia", "virginia"),
        State("Washington", "washington"), State("West Virginia", "west-virginia"),
        State("Wisconsin", "wisconsin"), State("Wyoming", "wyoming"),
        State("Washington DC", "district-of-columbia")
    )

    fun url(state: State): String = BASE + state.file + ".map"

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "maps").apply { mkdirs() }

    fun fileFor(ctx: Context, state: State): File = File(dir(ctx), state.file + ".map")

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- download tracking --------------------------------------------------
    // DownloadManager writes to the final path while still in flight, so a file
    // existing is not enough — completion is tracked explicitly.

    fun pendingId(ctx: Context, state: State): Long =
        prefs(ctx).getLong("dl_" + state.file, -1L)

    fun setPending(ctx: Context, state: State, id: Long) {
        prefs(ctx).edit().putLong("dl_" + state.file, id).apply()
    }

    fun clearPending(ctx: Context, state: State) {
        prefs(ctx).edit().remove("dl_" + state.file).apply()
    }

    fun markDone(ctx: Context, state: State) {
        val done = HashSet(prefs(ctx).getStringSet("done", emptySet()) ?: emptySet())
        done.add(state.file)
        prefs(ctx).edit().putStringSet("done", done).remove("dl_" + state.file).apply()
    }

    fun markGone(ctx: Context, state: State) {
        val done = HashSet(prefs(ctx).getStringSet("done", emptySet()) ?: emptySet())
        done.remove(state.file)
        prefs(ctx).edit().putStringSet("done", done).apply()
    }

    fun isDone(ctx: Context, state: State): Boolean {
        if (!fileFor(ctx, state).exists()) return false
        val done = prefs(ctx).getStringSet("done", emptySet()) ?: emptySet()
        // A file with no record and no in-flight download is treated as complete
        // (e.g. prefs were cleared but the download survived).
        return done.contains(state.file) || pendingId(ctx, state) < 0
    }

    /** Completed state files, ready to hand to the renderer. */
    fun readyFiles(ctx: Context): List<File> =
        STATES.filter { isDone(ctx, it) }.map { fileFor(ctx, it) }

    /** Reconcile finished/cancelled DownloadManager jobs. Cheap; call freely. */
    fun refresh(ctx: Context) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        for (state in STATES) {
            val id = pendingId(ctx, state)
            if (id < 0) continue
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            var status = -1
            cursor.use { if (it.moveToFirst()) status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> markDone(ctx, state)
                -1, DownloadManager.STATUS_FAILED -> {
                    clearPending(ctx, state)
                    fileFor(ctx, state).delete()
                }
            }
        }
    }

    /** Bytes downloaded so far for an in-flight state, with total when known. */
    fun progress(ctx: Context, state: State): Pair<Long, Long>? {
        val id = pendingId(ctx, state)
        if (id < 0) return null
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        cursor.use {
            if (!it.moveToFirst()) return null
            val got = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return Pair(got, total)
        }
    }

    fun startDownload(ctx: Context, state: State) {
        fileFor(ctx, state).delete()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url(state)))
            .setTitle("Silage Loads map: " + state.label)
            .setDescription("Offline map for " + state.label)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setDestinationInExternalFilesDir(ctx, "maps", state.file + ".map")
        setPending(ctx, state, dm.enqueue(request))
    }

    fun cancel(ctx: Context, state: State) {
        val id = pendingId(ctx, state)
        if (id >= 0) {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(id)
        }
        clearPending(ctx, state)
        fileFor(ctx, state).delete()
    }

    fun delete(ctx: Context, state: State) {
        fileFor(ctx, state).delete()
        markGone(ctx, state)
    }

    fun fmtBytes(b: Long): String = when {
        b >= 1_000_000_000L -> String.format("%.1f GB", b / 1_000_000_000.0)
        b >= 1_000_000L -> String.format("%.0f MB", b / 1_000_000.0)
        b >= 1_000L -> String.format("%.0f KB", b / 1_000.0)
        else -> "$b B"
    }
}
