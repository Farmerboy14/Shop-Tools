package com.farmerboy.silageloads

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject

/**
 * Everything shared between drivers goes through here: identity (name +
 * vehicle), shared jobs with a join code, per-driver loads, live positions.
 *
 * Dormant until the app is built with a Firebase config: [configured] is then
 * false and every call is a quiet no-op, so the rest of the app never has to
 * care. Identity is anonymous Firebase auth — a per-phone account with no
 * password, which is the right weight for a crew of drivers.
 *
 * Data shape, one node per shared job:
 *   /jobs/{CODE}/info        { name, created }
 *   /jobs/{CODE}/zone        { lat, lng, r }
 *   /jobs/{CODE}/members/{uid}   { name, vehicle, joined }
 *   /jobs/{CODE}/loads/{push}    { uid, name, t, lat?, lng?, auto }
 *   /jobs/{CODE}/pos/{uid}       { name, vehicle, lat, lng, t }
 */
object Sync {

    private const val PREFS = "silage_crew"
    private const val CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // no 0/O/1/I/L

    data class Driver(
        val uid: String, val name: String, val vehicle: String, val driver: String,
        val lat: Double, val lng: Double, val t: Long
    ) {
        /** "JAKE · TRUCK 3" when a shift driver is set, else the account name. */
        fun label(): String {
            val who = driver.ifEmpty { name }
            val rig = if (driver.isEmpty()) vehicle else listOf(name, vehicle)
                .firstOrNull { it.isNotEmpty() && it != who } ?: ""
            return if (rig.isEmpty()) who else "$who · $rig"
        }
    }

    /** Handle for detaching a live listener. */
    class Listening internal constructor(
        private val ref: DatabaseReference,
        private val listener: ValueEventListener
    ) {
        fun stop() = ref.removeEventListener(listener)
    }

    // ---- availability -------------------------------------------------------

    fun configured(ctx: Context): Boolean =
        FirebaseApp.getApps(ctx.applicationContext).isNotEmpty()

    private var persistenceOn = false

    private fun db(ctx: Context): FirebaseDatabase {
        val database = FirebaseDatabase.getInstance()
        if (!persistenceOn) {
            persistenceOn = true
            try {
                database.setPersistenceEnabled(true)  // ride out dead zones
            } catch (_: Exception) {
                // already initialised; fine
            }
        }
        return database
    }

    private fun jobs(ctx: Context): DatabaseReference = db(ctx).reference.child("jobs")

    private fun signIn(ctx: Context, then: (String?) -> Unit) {
        if (!configured(ctx)) { then(null); return }
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user != null) { then(user.uid); return }
        auth.signInAnonymously()
            .addOnSuccessListener { then(it.user?.uid) }
            .addOnFailureListener { then(null) }
    }

    // ---- profile ------------------------------------------------------------

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun myName(ctx: Context): String = prefs(ctx).getString("name", "") ?: ""
    fun myVehicle(ctx: Context): String = prefs(ctx).getString("vehicle", "") ?: ""

    // ---- shift driver (truck-phone mode) ------------------------------------

    fun truckMode(ctx: Context): Boolean = prefs(ctx).getBoolean("truck_mode", false)

    fun setTruckMode(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("truck_mode", on).apply()
    }

    fun myDriver(ctx: Context): String = prefs(ctx).getString("driver", "") ?: ""

    /** When today's driver was last picked; a new day means asking again. */
    fun driverPickedToday(ctx: Context): Boolean {
        val when_ = prefs(ctx).getString("driver_day", "")
        return myDriver(ctx).isNotEmpty() && when_ == LoadStore.todayKey()
    }

    fun setDriver(ctx: Context, driver: String) {
        prefs(ctx).edit()
            .putString("driver", driver)
            .putString("driver_day", LoadStore.todayKey())
            .apply()
        // add to the job roster so every truck offers this name
        if (!configured(ctx) || driver.isEmpty()) return
        val code = LoadStore.activeJob(LoadStore.read(ctx)).optString("share")
        if (code.isEmpty()) return
        signIn(ctx) { uid ->
            uid ?: return@signIn
            val key = driver.replace(Regex("[.#$\\[\\]/]"), " ").trim()
            if (key.isEmpty()) return@signIn
            jobs(ctx).child(code).child("roster").child(key)
                .setValue(mapOf<String, Any>("name" to driver, "t" to System.currentTimeMillis()))
        }
    }

    /** Names to offer on the who's-driving screen: roster + members. */
    fun listRoster(ctx: Context, code: String, done: (List<String>) -> Unit) {
        if (!configured(ctx)) { done(emptyList()); return }
        signIn(ctx) { uid ->
            if (uid == null) { done(emptyList()); return@signIn }
            jobs(ctx).child(code).get()
                .addOnSuccessListener { snap ->
                    val names = LinkedHashSet<String>()
                    for (child in snap.child("roster").children) {
                        child.child("name").getValue(String::class.java)?.let { names.add(it) }
                    }
                    for (child in snap.child("members").children) {
                        child.child("name").getValue(String::class.java)
                            ?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
                    }
                    done(names.toList().sorted())
                }
                .addOnFailureListener { done(emptyList()) }
        }
    }

    /** Today's shared loads, tallied per driver, newest job state. */
    fun crewToday(ctx: Context, code: String, done: (List<Pair<String, Int>>, Int) -> Unit) {
        if (!configured(ctx)) { done(emptyList(), 0); return }
        signIn(ctx) { uid ->
            if (uid == null) { done(emptyList(), 0); return@signIn }
            val startOfDay = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            jobs(ctx).child(code).child("loads")
                .orderByChild("t").startAt(startOfDay.toDouble())
                .get()
                .addOnSuccessListener { snap ->
                    val counts = LinkedHashMap<String, Int>()
                    var total = 0
                    for (child in snap.children) {
                        val who = child.child("driver").getValue(String::class.java)
                            ?.takeIf { it.isNotEmpty() }
                            ?: child.child("name").getValue(String::class.java)
                            ?.takeIf { it.isNotEmpty() } ?: "Unknown"
                        counts[who] = (counts[who] ?: 0) + 1
                        total++
                    }
                    done(counts.entries.sortedByDescending { it.value }.map { Pair(it.key, it.value) }, total)
                }
                .addOnFailureListener { done(emptyList(), 0) }
        }
    }

    fun saveProfile(ctx: Context, name: String, vehicle: String) {
        prefs(ctx).edit().putString("name", name).putString("vehicle", vehicle).apply()
        // refresh the member records of any shared jobs we're in
        if (!configured(ctx)) return
        signIn(ctx) { uid ->
            uid ?: return@signIn
            val state = LoadStore.read(ctx)
            val all = LoadStore.jobs(state)
            for (i in 0 until all.length()) {
                val code = all.getJSONObject(i).optString("share")
                if (code.isNotEmpty()) {
                    jobs(ctx).child(code).child("members").child(uid)
                        .updateChildren(mapOf<String, Any>("name" to name, "vehicle" to vehicle))
                }
            }
        }
    }

    // ---- share / join -------------------------------------------------------

    fun createShare(ctx: Context, job: JSONObject, done: (String?) -> Unit) {
        if (!configured(ctx)) { done(null); return }
        signIn(ctx) { uid ->
            if (uid == null) { done(null); return@signIn }
            val code = (1..6).map { CODE_CHARS.random() }.joinToString("")
            val node = jobs(ctx).child(code)
            val payload = HashMap<String, Any>()
            payload["info"] = mapOf<String, Any>(
                "name" to job.optString("name", "Job"),
                "created" to System.currentTimeMillis(),
                "owner" to uid
            )
            LoadStore.zone(job)?.let { z ->
                payload["zone"] = mapOf<String, Any>(
                    "lat" to z.optDouble("lat"), "lng" to z.optDouble("lng"),
                    "r" to z.optInt("r", 152)
                )
            }
            payload["members/$uid"] = mapOf<String, Any>(
                "name" to myName(ctx), "vehicle" to myVehicle(ctx),
                "joined" to System.currentTimeMillis()
            )
            node.updateChildren(payload)
                .addOnSuccessListener { done(code) }
                .addOnFailureListener { done(null) }
        }
    }

    /** Look up a code; returns (jobName, zoneJsonOrNull) or (null, null). */
    fun join(ctx: Context, rawCode: String, done: (String?, JSONObject?) -> Unit) {
        if (!configured(ctx)) { done(null, null); return }
        val code = rawCode.trim().uppercase()
        signIn(ctx) { uid ->
            if (uid == null) { done(null, null); return@signIn }
            jobs(ctx).child(code).get()
                .addOnSuccessListener { snap ->
                    if (!snap.exists()) { done(null, null); return@addOnSuccessListener }
                    val name = snap.child("info/name").getValue(String::class.java) ?: "Shared job"
                    val zone = snap.child("zone").let { z ->
                        if (!z.exists()) null else JSONObject()
                            .put("lat", z.child("lat").getValue(Double::class.java) ?: 0.0)
                            .put("lng", z.child("lng").getValue(Double::class.java) ?: 0.0)
                            .put("r", (z.child("r").getValue(Long::class.java) ?: 152L).toInt())
                            .put("auto", false)
                    }
                    jobs(ctx).child(code).child("members").child(uid).setValue(
                        mapOf<String, Any>(
                            "name" to myName(ctx), "vehicle" to myVehicle(ctx),
                            "joined" to System.currentTimeMillis()
                        )
                    )
                    done(name, zone)
                }
                .addOnFailureListener { done(null, null) }
        }
    }

    /** Push the active job's current zone so every member gets the same spot. */
    fun publishZone(ctx: Context) {
        if (!configured(ctx)) return
        val job = LoadStore.activeJob(LoadStore.read(ctx))
        val code = job.optString("share")
        val zone = LoadStore.zone(job)
        if (code.isEmpty()) return
        signIn(ctx) { uid ->
            uid ?: return@signIn
            val node = jobs(ctx).child(code).child("zone")
            if (zone == null) node.removeValue()
            else node.setValue(
                mapOf<String, Any>(
                    "lat" to zone.optDouble("lat"), "lng" to zone.optDouble("lng"),
                    "r" to zone.optInt("r", 152)
                )
            )
        }
    }

    /** Live zone updates from the rest of the crew. */
    fun listenZone(ctx: Context, code: String, onZone: (JSONObject?) -> Unit): Listening? {
        if (!configured(ctx)) return null
        val ref = jobs(ctx).child(code).child("zone")
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!snap.exists()) { onZone(null); return }
                onZone(
                    JSONObject()
                        .put("lat", snap.child("lat").getValue(Double::class.java) ?: 0.0)
                        .put("lng", snap.child("lng").getValue(Double::class.java) ?: 0.0)
                        .put("r", (snap.child("r").getValue(Long::class.java) ?: 152L).toInt())
                )
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return Listening(ref, listener)
    }

    // ---- loads and positions ------------------------------------------------

    /** Record a load against the active job's shared log, if it has one. */
    fun pushLoadForActive(ctx: Context, auto: Boolean, lat: Double?, lng: Double?) {
        if (!configured(ctx)) return
        val code = LoadStore.activeJob(LoadStore.read(ctx)).optString("share")
        if (code.isEmpty()) return
        signIn(ctx) { uid ->
            uid ?: return@signIn
            val load = HashMap<String, Any>()
            load["uid"] = uid
            load["name"] = myName(ctx)
            load["driver"] = myDriver(ctx)
            load["t"] = System.currentTimeMillis()
            load["auto"] = auto
            if (lat != null && lng != null) { load["lat"] = lat; load["lng"] = lng }
            jobs(ctx).child(code).child("loads").push().setValue(load)
        }
    }

    private var lastPosPush = 0L

    /** Share where this truck is, at most every 20 s. */
    fun publishPosition(ctx: Context, lat: Double, lng: Double) {
        if (!configured(ctx)) return
        val now = System.currentTimeMillis()
        if (now - lastPosPush < 20_000L) return
        val code = LoadStore.activeJob(LoadStore.read(ctx)).optString("share")
        if (code.isEmpty()) return
        lastPosPush = now
        signIn(ctx) { uid ->
            uid ?: return@signIn
            jobs(ctx).child(code).child("pos").child(uid).setValue(
                mapOf<String, Any>(
                    "name" to myName(ctx), "vehicle" to myVehicle(ctx),
                    "driver" to myDriver(ctx),
                    "lat" to lat, "lng" to lng, "t" to now
                )
            )
        }
    }

    /** Everyone else's trucks, live. Stale entries (>10 min) are filtered. */
    fun listenPositions(ctx: Context, code: String, onDrivers: (List<Driver>) -> Unit): Listening? {
        if (!configured(ctx)) return null
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
        val ref = jobs(ctx).child(code).child("pos")
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val now = System.currentTimeMillis()
                val drivers = ArrayList<Driver>()
                for (child in snap.children) {
                    val uid = child.key ?: continue
                    if (uid == myUid) continue
                    val t = child.child("t").getValue(Long::class.java) ?: 0L
                    if (now - t > 10 * 60_000L) continue
                    val lat = child.child("lat").getValue(Double::class.java) ?: continue
                    val lng = child.child("lng").getValue(Double::class.java) ?: continue
                    val name = child.child("name").getValue(String::class.java) ?: "Driver"
                    val vehicle = child.child("vehicle").getValue(String::class.java) ?: ""
                    val shiftDriver = child.child("driver").getValue(String::class.java) ?: ""
                    drivers.add(Driver(uid, name, vehicle, shiftDriver, lat, lng, t))
                }
                onDrivers(drivers)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return Listening(ref, listener)
    }

    /** Compass letter from one point toward another (N, NE, E, …). */
    fun compass(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): String {
        val dLng = Math.toRadians(toLng - fromLng)
        val y = Math.sin(dLng) * Math.cos(Math.toRadians(toLat))
        val x = Math.cos(Math.toRadians(fromLat)) * Math.sin(Math.toRadians(toLat)) -
            Math.sin(Math.toRadians(fromLat)) * Math.cos(Math.toRadians(toLat)) * Math.cos(dLng)
        val deg = (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[((deg + 22.5) / 45.0).toInt() % 8]
    }
}
