package com.farmerboy.silageloads

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max

/**
 * One JSON blob in SharedPreferences, in the same shape the web version keeps in
 * localStorage, so counts can be moved between them:
 *
 *   { "active": "<jobId>",
 *     "jobs": [ { "id", "name", "created": "yyyy-MM-dd",
 *                 "zone": { "lat", "lng", "r", "auto" } | absent,
 *                 "days": { "yyyy-MM-dd": { "count": N,
 *                                           "loads": [ {"t", "lat", "lng", "auto"} ] } } } ] }
 *
 * Every component (activity, widget, geofence receiver) reads and writes through
 * here, so a load counted in the background shows up everywhere.
 */
object LoadStore {

    private const val PREFS = "silage_loads"
    private const val KEY_STATE = "state"

    // ---- date helpers -------------------------------------------------------

    fun todayKey(): String = LocalDate.now().toString()
    fun yesterdayKey(): String = LocalDate.now().minusDays(1).toString()

    // ---- read / write -------------------------------------------------------

    @Synchronized
    fun read(ctx: Context): JSONObject {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_STATE, null)
        if (raw != null) {
            try {
                val state = JSONObject(raw)
                if ((state.optJSONArray("jobs")?.length() ?: 0) > 0) return migrate(state)
            } catch (_: Exception) {
                // fall through and start fresh rather than crash on corrupt data
            }
        }
        val fresh = JSONObject()
        val jobs = JSONArray()
        val job = newJob("Job 1")
        jobs.put(job)
        fresh.put("jobs", jobs)
        fresh.put("active", job.getString("id"))
        write(ctx, fresh)
        return fresh
    }

    @Synchronized
    fun write(ctx: Context, state: JSONObject) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATE, state.toString()).apply()
    }

    /** Older payloads stored plain timestamps in `times`; loads are objects now. */
    private fun migrate(state: JSONObject): JSONObject {
        val jobs = state.optJSONArray("jobs") ?: return state
        for (i in 0 until jobs.length()) {
            val days = jobs.getJSONObject(i).optJSONObject("days") ?: continue
            val keys = days.keys().asSequence().toList()
            for (k in keys) {
                val day = days.getJSONObject(k)
                if (!day.has("loads")) {
                    val loads = JSONArray()
                    val times = day.optJSONArray("times")
                    if (times != null) {
                        for (j in 0 until times.length()) {
                            loads.put(JSONObject().put("t", times.optLong(j)))
                        }
                    }
                    day.put("loads", loads)
                }
                day.remove("times")
            }
        }
        return state
    }

    // ---- jobs ---------------------------------------------------------------

    fun newJob(name: String): JSONObject = JSONObject()
        .put("id", "j" + java.util.UUID.randomUUID().toString().take(8))
        .put("name", name)
        .put("created", todayKey())
        .put("days", JSONObject())

    fun jobs(state: JSONObject): JSONArray = state.optJSONArray("jobs") ?: JSONArray()

    fun activeJob(state: JSONObject): JSONObject {
        val jobs = jobs(state)
        val active = state.optString("active")
        for (i in 0 until jobs.length()) {
            val job = jobs.getJSONObject(i)
            if (job.optString("id") == active) return job
        }
        return jobs.getJSONObject(0)
    }

    fun jobById(state: JSONObject, id: String): JSONObject? {
        val jobs = jobs(state)
        for (i in 0 until jobs.length()) {
            val job = jobs.getJSONObject(i)
            if (job.optString("id") == id) return job
        }
        return null
    }

    // ---- counts -------------------------------------------------------------

    private fun day(job: JSONObject, key: String, create: Boolean): JSONObject? {
        val days = job.optJSONObject("days") ?: JSONObject().also { job.put("days", it) }
        val existing = days.optJSONObject(key)
        if (existing != null) return existing
        if (!create) return null
        val fresh = JSONObject().put("count", 0).put("loads", JSONArray())
        days.put(key, fresh)
        return fresh
    }

    fun dayCount(job: JSONObject, key: String): Int = day(job, key, false)?.optInt("count") ?: 0

    fun todayCount(ctx: Context): Int {
        val state = read(ctx)
        return dayCount(activeJob(state), todayKey())
    }

    fun jobTotal(job: JSONObject): Int {
        val days = job.optJSONObject("days") ?: return 0
        var total = 0
        for (k in days.keys()) total += days.getJSONObject(k).optInt("count")
        return total
    }

    fun loadsFor(job: JSONObject, key: String): JSONArray =
        day(job, key, false)?.optJSONArray("loads") ?: JSONArray()

    /** Day keys that have loads, newest first (today always included). */
    fun dayKeys(job: JSONObject): List<String> {
        val days = job.optJSONObject("days") ?: return listOf(todayKey())
        val keys = days.keys().asSequence()
            .filter { days.getJSONObject(it).optInt("count") > 0 || it == todayKey() }
            .toMutableList()
        if (!keys.contains(todayKey())) keys.add(todayKey())
        return keys.sortedDescending()
    }

    /**
     * Add one load to today for [jobId] (or the active job when null).
     * [auto] marks geofence-counted loads. Returns the new day count.
     */
    @Synchronized
    fun addLoad(
        ctx: Context,
        jobId: String? = null,
        auto: Boolean = false,
        lat: Double? = null,
        lng: Double? = null
    ): Int {
        val state = read(ctx)
        val job = jobId?.let { jobById(state, it) } ?: activeJob(state)
        val key = todayKey()
        val d = day(job, key, true)!!
        d.put("count", d.optInt("count") + 1)
        val entry = JSONObject().put("t", System.currentTimeMillis())
        if (lat != null && lng != null) {
            entry.put("lat", round5(lat)).put("lng", round5(lng))
        }
        if (auto) entry.put("auto", true)
        d.optJSONArray("loads")?.put(entry)
        write(ctx, state)
        return d.optInt("count")
    }

    /** Remove the most recent load from [key]; never goes below zero. */
    @Synchronized
    fun removeLoad(ctx: Context, jobId: String? = null, key: String = todayKey()): Int {
        val state = read(ctx)
        val job = jobId?.let { jobById(state, it) } ?: activeJob(state)
        val d = day(job, key, false) ?: return 0
        d.put("count", max(0, d.optInt("count") - 1))
        val loads = d.optJSONArray("loads")
        if (loads != null && loads.length() > 0) loads.remove(loads.length() - 1)
        write(ctx, state)
        return d.optInt("count")
    }

    /** Adjust a past day by hand; these corrections are not location-stamped. */
    @Synchronized
    fun adjustDay(ctx: Context, jobId: String, key: String, delta: Int) {
        val state = read(ctx)
        val job = jobById(state, jobId) ?: return
        val d = day(job, key, true)!!
        d.put("count", max(0, d.optInt("count") + delta))
        if (delta < 0) {
            val loads = d.optJSONArray("loads")
            if (loads != null && loads.length() > d.optInt("count")) loads.remove(loads.length() - 1)
        }
        write(ctx, state)
    }

    // ---- zone ---------------------------------------------------------------

    fun zone(job: JSONObject): JSONObject? = job.optJSONObject("zone")

    @Synchronized
    fun setZone(ctx: Context, lat: Double, lng: Double, radiusM: Int) {
        val state = read(ctx)
        val job = activeJob(state)
        val existing = zone(job)
        job.put(
            "zone", JSONObject()
                .put("lat", round5(lat))
                .put("lng", round5(lng))
                .put("r", radiusM)
                .put("auto", existing?.optBoolean("auto") ?: false)
        )
        write(ctx, state)
    }

    @Synchronized
    fun setZoneRadius(ctx: Context, radiusM: Int) {
        val state = read(ctx)
        zone(activeJob(state))?.put("r", radiusM) ?: return
        write(ctx, state)
    }

    @Synchronized
    fun setAuto(ctx: Context, on: Boolean) {
        val state = read(ctx)
        zone(activeJob(state))?.put("auto", on) ?: return
        write(ctx, state)
    }

    @Synchronized
    fun clearZone(ctx: Context) {
        val state = read(ctx)
        activeJob(state).remove("zone")
        write(ctx, state)
    }

    private fun round5(v: Double): Double = Math.round(v * 100000.0) / 100000.0
}
