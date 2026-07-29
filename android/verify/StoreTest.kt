import android.content.Context
import com.farmerboy.silageloads.LoadStore
import org.json.JSONArray
import org.json.JSONObject

/** Exercises the real LoadStore logic against an in-memory Context. */
object StoreTest {

    private var failures = 0

    private fun check(cond: Boolean, msg: String) {
        if (cond) println("ok: $msg") else { failures++; println("FAIL: $msg") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val ctx = Context()
        val today = LoadStore.todayKey()
        val yesterday = LoadStore.yesterdayKey()

        // --- fresh install -------------------------------------------------
        var state = LoadStore.read(ctx)
        check(LoadStore.jobs(state).length() == 1, "fresh install creates one job")
        check(LoadStore.dayCount(LoadStore.activeJob(state), today) == 0, "starts at zero")

        // --- counting ------------------------------------------------------
        repeat(3) { LoadStore.addLoad(ctx) }
        check(LoadStore.todayCount(ctx) == 3, "three manual loads -> 3")
        LoadStore.removeLoad(ctx)
        check(LoadStore.todayCount(ctx) == 2, "undo -> 2")
        repeat(5) { LoadStore.removeLoad(ctx) }
        check(LoadStore.todayCount(ctx) == 0, "undo never goes negative")

        // loads array must stay in step with the count
        state = LoadStore.read(ctx)
        check(LoadStore.loadsFor(LoadStore.activeJob(state), today).length() == 0,
            "load records removed along with the count")

        // --- auto load with coordinates -------------------------------------
        LoadStore.addLoad(ctx, auto = true, lat = 43.612345, lng = -96.543215)
        state = LoadStore.read(ctx)
        val loads = LoadStore.loadsFor(LoadStore.activeJob(state), today)
        check(loads.length() == 1, "auto load recorded")
        val entry = loads.getJSONObject(0)
        check(entry.optBoolean("auto"), "auto load tagged auto")
        check(entry.optDouble("lat") == 43.61235 || entry.optDouble("lat") == 43.61234,
            "latitude rounded to 5dp, got ${entry.optDouble("lat")}")
        check(entry.optLong("t") > 0L, "timestamp stamped")

        // --- totals ----------------------------------------------------------
        check(LoadStore.jobTotal(LoadStore.activeJob(LoadStore.read(ctx))) == 1, "job total counts today")

        // --- past-day adjustment ---------------------------------------------
        val jobId = LoadStore.activeJob(LoadStore.read(ctx)).optString("id")
        LoadStore.adjustDay(ctx, jobId, yesterday, +10)
        state = LoadStore.read(ctx)
        check(LoadStore.dayCount(LoadStore.activeJob(state), yesterday) == 10, "yesterday adjusted to 10")
        check(LoadStore.jobTotal(LoadStore.activeJob(state)) == 11, "job total spans days")
        LoadStore.adjustDay(ctx, jobId, yesterday, -1)
        check(LoadStore.dayCount(LoadStore.activeJob(LoadStore.read(ctx)), yesterday) == 9, "yesterday back to 9")

        // --- day keys ordering -------------------------------------------------
        val keys = LoadStore.dayKeys(LoadStore.activeJob(LoadStore.read(ctx)))
        check(keys.isNotEmpty() && keys[0] == today, "day keys newest first, today leads")
        check(keys.contains(yesterday), "day keys include yesterday")

        // --- zone ---------------------------------------------------------------
        LoadStore.setZone(ctx, 43.6, -96.5, 152)
        state = LoadStore.read(ctx)
        var zone = LoadStore.zone(LoadStore.activeJob(state))
        check(zone != null && zone.optInt("r") == 152, "zone stored with radius")
        check(zone != null && !zone.optBoolean("auto"), "zone starts with auto off")
        LoadStore.setAuto(ctx, true)
        zone = LoadStore.zone(LoadStore.activeJob(LoadStore.read(ctx)))
        check(zone != null && zone.optBoolean("auto"), "auto toggles on")
        LoadStore.setZone(ctx, 44.0, -97.0, 152)
        zone = LoadStore.zone(LoadStore.activeJob(LoadStore.read(ctx)))
        check(zone != null && zone.optBoolean("auto"), "moving the zone keeps auto on")
        LoadStore.setZoneRadius(ctx, 402)
        zone = LoadStore.zone(LoadStore.activeJob(LoadStore.read(ctx)))
        check(zone != null && zone.optInt("r") == 402, "radius updated in place")
        LoadStore.clearZone(ctx)
        check(LoadStore.zone(LoadStore.activeJob(LoadStore.read(ctx))) == null, "zone cleared")

        // --- multiple jobs stay separate ------------------------------------------
        state = LoadStore.read(ctx)
        val second = LoadStore.newJob("North Field")
        LoadStore.jobs(state).put(second)
        state.put("active", second.getString("id"))
        LoadStore.write(ctx, state)
        check(LoadStore.todayCount(ctx) == 0, "new job starts at zero")
        LoadStore.addLoad(ctx)
        check(LoadStore.todayCount(ctx) == 1, "new job counts independently")
        state = LoadStore.read(ctx)
        state.put("active", jobId)
        LoadStore.write(ctx, state)
        check(LoadStore.todayCount(ctx) == 1, "original job's today count intact after switching back")
        check(LoadStore.dayCount(LoadStore.activeJob(LoadStore.read(ctx)), yesterday) == 9,
            "original job's history intact")

        // adding to a specific job by id must not touch the active one
        LoadStore.addLoad(ctx, jobId = second.getString("id"))
        check(LoadStore.todayCount(ctx) == 1, "targeted add left the active job alone")
        val secondJob = LoadStore.jobById(LoadStore.read(ctx), second.getString("id"))!!
        check(LoadStore.dayCount(secondJob, today) == 2, "targeted add landed on the right job")

        // --- migration from the web app's older `times` format ---------------------
        Context.reset()
        val fresh = Context()
        val legacy = JSONObject()
            .put("active", "jold")
            .put(
                "jobs", JSONArray().put(
                    JSONObject()
                        .put("id", "jold").put("name", "Old Job").put("created", "2026-07-20")
                        .put(
                            "days", JSONObject().put(
                                "2026-07-27",
                                JSONObject().put("count", 10)
                                    .put("times", JSONArray().put(1785200000000L).put(1785203600000L))
                            )
                        )
                )
            )
        LoadStore.write(fresh, legacy)
        val migrated = LoadStore.read(fresh)
        val oldJob = LoadStore.activeJob(migrated)
        check(LoadStore.dayCount(oldJob, "2026-07-27") == 10, "migration keeps the count")
        val migratedLoads = LoadStore.loadsFor(oldJob, "2026-07-27")
        check(migratedLoads.length() == 2, "old timestamps became load records")
        check(migratedLoads.getJSONObject(0).optLong("t") == 1785200000000L, "timestamp preserved")
        check(!oldJob.getJSONObject("days").getJSONObject("2026-07-27").has("times"),
            "legacy times array dropped")

        // --- corrupt data must not crash --------------------------------------------
        Context.reset()
        val broken = Context()
        broken.getSharedPreferences("silage_loads", Context.MODE_PRIVATE)
            .edit().putString("state", "{not json at all").apply()
        check(LoadStore.read(broken).let { LoadStore.jobs(it).length() == 1 },
            "corrupt stored data recovers to a fresh job")

        println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILURE(S)")
        if (failures > 0) System.exit(1)
    }
}
