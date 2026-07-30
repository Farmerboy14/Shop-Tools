package com.farmerboy.silageloads

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private data class Radius(val label: String, val meters: Int)

    private val radii = listOf(
        Radius("300 FT", 91), Radius("500 FT", 152),
        Radius("1000 FT", 305), Radius("¼ MI", 402)
    )

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val clockFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())
    private val ampmFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("a", Locale.getDefault())
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
    private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

    private val ui = Handler(Looper.getMainLooper())
    private val expanded = HashSet<String>()

    /** Radius chosen before any zone exists. */
    private var pendingRadius = 152

    private var awakeWanted = false
    private var dimWanted = false
    private var dimShown = false
    private var pendingRadiusOnly = false

    private lateinit var prefs: android.content.SharedPreferences

    private val clockTick = object : Runnable {
        override fun run() {
            tickClock()
            ui.postDelayed(this, 60_000L - (System.currentTimeMillis() % 60_000L) + 250L)
        }
    }

    private val dimTick = Runnable { showDim() }

    // views
    private lateinit var scroller: ScrollView
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var jobName: TextView
    private lateinit var jobStarted: TextView
    private lateinit var todayLbl: TextView
    private lateinit var todayCount: TextView
    private lateinit var lastLoad: TextView
    private lateinit var yestCount: TextView
    private lateinit var jobTotal: TextView
    private lateinit var histList: LinearLayout
    private lateinit var zoneInfo: TextView
    private lateinit var radiusRow: LinearLayout
    private lateinit var autoBtn: Button
    private lateinit var zoneSetBtn: Button
    private lateinit var zoneClearBtn: Button
    private lateinit var permBtn: Button
    private lateinit var crewInfo: TextView
    private lateinit var shareJobBtn: Button
    private lateinit var dimCrew: LinearLayout
    private var posListening: Sync.Listening? = null
    private var zoneListening: Sync.Listening? = null
    private var crewDrivers: List<Sync.Driver> = emptyList()
    private var lastOwnLat: Double? = null
    private var lastOwnLng: Double? = null
    private val dimCrewTick = object : Runnable {
        override fun run() {
            refreshOwnFixForDim()
            ui.postDelayed(this, 60_000L)
        }
    }
    private lateinit var coordInput: EditText
    private lateinit var awakeBtn: Button
    private lateinit var dimBtn: Button
    private lateinit var dimOverlay: LinearLayout
    private lateinit var dimCount: TextView
    private lateinit var dimClock: TextView

    private val askFineLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (pendingRadiusOnly) {
                pendingRadiusOnly = false
                captureZoneHere()
            } else {
                askBackgroundIfNeeded()
            }
        } else {
            zoneInfo.text = "Location permission denied — auto counting needs it."
        }
    }

    private val askBackground = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        GeofenceManager.sync(this)
        render()
    }

    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("silage_ui", Context.MODE_PRIVATE)

        scroller = findViewById(R.id.scroller)
        clockTime = findViewById(R.id.clockTime)
        clockDate = findViewById(R.id.clockDate)
        jobName = findViewById(R.id.jobName)
        jobStarted = findViewById(R.id.jobStarted)
        todayLbl = findViewById(R.id.todayLbl)
        todayCount = findViewById(R.id.todayCount)
        lastLoad = findViewById(R.id.lastLoad)
        yestCount = findViewById(R.id.yestCount)
        jobTotal = findViewById(R.id.jobTotal)
        histList = findViewById(R.id.histList)
        zoneInfo = findViewById(R.id.zoneInfo)
        radiusRow = findViewById(R.id.radiusRow)
        autoBtn = findViewById(R.id.autoBtn)
        zoneSetBtn = findViewById(R.id.zoneSetBtn)
        zoneClearBtn = findViewById(R.id.zoneClearBtn)
        permBtn = findViewById(R.id.permBtn)
        crewInfo = findViewById(R.id.crewInfo)
        shareJobBtn = findViewById(R.id.shareJobBtn)
        dimCrew = findViewById(R.id.dimCrew)
        coordInput = findViewById(R.id.coordInput)
        awakeBtn = findViewById(R.id.awakeBtn)
        dimBtn = findViewById(R.id.dimBtn)
        dimOverlay = findViewById(R.id.dimOverlay)
        dimCount = findViewById(R.id.dimCount)
        dimClock = findViewById(R.id.dimClock)

        awakeWanted = prefs.getBoolean("awake", false)
        dimWanted = prefs.getBoolean("dim", false)

        findViewById<Button>(R.id.bigBtn).setOnClickListener {
            LoadStore.addLoad(this)
            Sync.pushLoadForActive(this, auto = false, lat = null, lng = null)
            buzz(30)
            LoadWidget.refresh(this)
            render()
        }
        findViewById<Button>(R.id.accountBtn).setOnClickListener {
            startActivity(Intent(this, AccountActivity::class.java))
        }
        shareJobBtn.setOnClickListener { shareFlow() }
        findViewById<Button>(R.id.joinJobBtn).setOnClickListener { joinFlow() }
        findViewById<Button>(R.id.driverBtn).setOnClickListener { showDriverPicker() }
        findViewById<Button>(R.id.crewTodayBtn).setOnClickListener { showCrewToday() }
        findViewById<Button>(R.id.undoBtn).setOnClickListener {
            LoadStore.removeLoad(this)
            LoadWidget.refresh(this)
            render()
        }
        findViewById<Button>(R.id.mapBtn).setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        findViewById<Button>(R.id.jobsBtn).setOnClickListener { showJobsDialog() }
        findViewById<Button>(R.id.newJobBtn).setOnClickListener { showNewJobDialog() }

        autoBtn.setOnClickListener { toggleAuto() }
        zoneSetBtn.setOnClickListener { onSetZoneHere() }
        zoneClearBtn.setOnClickListener {
            LoadStore.clearZone(this)
            GeofenceManager.sync(this)
            Sync.publishZone(this)
            render()
        }
        findViewById<Button>(R.id.coordBtn).setOnClickListener { onSetZoneFromText() }
        permBtn.setOnClickListener { openAppLocationSettings() }

        awakeBtn.setOnClickListener {
            awakeWanted = !awakeWanted
            prefs.edit().putBoolean("awake", awakeWanted).apply()
            applyAwake()
        }
        dimBtn.setOnClickListener {
            dimWanted = !dimWanted
            prefs.edit().putBoolean("dim", dimWanted).apply()
            if (!dimWanted) hideDim() else armDim()
            paintToggles()
        }
        dimOverlay.setOnClickListener { hideDim() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        applyAwake()
        paintToggles()
        render()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(clockTick)
        clockTick.run()
        GeofenceManager.sync(this)
        ZoneWatchService.start(this)   // no-op unless a zone is set and auto is on
        attachCrewListeners()
        render()
        armDim()
        maybePromptDriver()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(clockTick)
        ui.removeCallbacks(dimTick)
        ui.removeCallbacks(dimCrewTick)
        detachCrewListeners()
    }

    /** Any touch anywhere restarts the idle countdown. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) armDim()
        return super.dispatchTouchEvent(ev)
    }

    // ---- rendering ----------------------------------------------------------

    private fun render() {
        val state = LoadStore.read(this)
        val job = LoadStore.activeJob(state)
        val today = LoadStore.todayKey()

        jobName.text = job.optString("name", "Job")
        jobStarted.text = "since " + niceDay(job.optString("created", today))
        todayLbl.text = "TODAY · " + niceDay(today).uppercase(Locale.getDefault())

        val count = LoadStore.dayCount(job, today)
        todayCount.text = count.toString()
        yestCount.text = LoadStore.dayCount(job, LoadStore.yesterdayKey()).toString()
        jobTotal.text = LoadStore.jobTotal(job).toString()

        val loads = LoadStore.loadsFor(job, today)
        lastLoad.text = if (loads.length() > 0) {
            "last load " + fmtTime(loads.getJSONObject(loads.length() - 1).optLong("t"))
        } else ""

        renderHistory(job)
        renderZone(job)
        renderCrew(job)
        if (dimShown) dimCount.text = count.toString()
    }

    // ---- crew: account, share, join ----------------------------------------

    private fun renderCrew(job: JSONObject) {
        val name = Sync.myName(this)
        val vehicle = Sync.myVehicle(this)
        val code = job.optString("share")
        val driver = Sync.myDriver(this)
        var you = if (name.isEmpty()) "Set your name in ACCOUNT so the crew knows who you are."
            else "You: $name" + (if (vehicle.isEmpty()) "" else " · $vehicle")
        if (driver.isNotEmpty()) you += "\nDriving: $driver"
        else if (Sync.truckMode(this)) you += "\nNo driver picked — tap WHO'S DRIVING."
        val line = when {
            !Sync.configured(this) ->
                "$you\nSharing needs a one-time setup in this build before codes work."
            code.isEmpty() ->
                "$you\nShare this job to get a code the crew can join with — they'll " +
                    "get the same dump spot, and you'll see each other while hauling."
            else -> {
                val others = crewDrivers.size
                "$you\nJob code: $code · " +
                    (if (others == 0) "no other drivers live right now"
                     else "$others other driver" + (if (others > 1) "s" else "") + " live")
            }
        }
        crewInfo.text = line
        shareJobBtn.text = if (code.isEmpty()) getString(R.string.share_job) else "CODE $code"
    }

    private fun shareFlow() {
        if (!Sync.configured(this)) {
            AlertDialog.Builder(this)
                .setTitle("Sharing isn't switched on yet")
                .setMessage(
                    "The crew features need a one-time (free) setup of the shared " +
                        "database this build was made without. Once it's in, this button " +
                        "makes a join code."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val job = LoadStore.activeJob(LoadStore.read(this))
        val existing = job.optString("share")
        if (existing.isNotEmpty()) { showCodeDialog(existing); return }
        if (Sync.myName(this).isEmpty()) {
            startActivity(Intent(this, AccountActivity::class.java))
            return
        }
        Sync.createShare(this, job) { code ->
            if (code == null) {
                crewInfo.text = "Couldn't create the share — check signal and try again."
                return@createShare
            }
            val state = LoadStore.read(this)
            LoadStore.activeJob(state).put("share", code)
            LoadStore.write(this, state)
            attachCrewListeners()
            render()
            showCodeDialog(code)
        }
    }

    private fun showCodeDialog(code: String) {
        AlertDialog.Builder(this)
            .setTitle("Job code")
            .setMessage(
                "\n        $code\n\nText this to your drivers. In their app: " +
                    "JOIN A JOB → type the code. They get this job with the same " +
                    "dump spot, and everyone shows up on each other's crew list."
            )
            .setPositiveButton("Done", null)
            .show()
    }

    private fun joinFlow() {
        if (!Sync.configured(this)) { shareFlow(); return }
        if (Sync.myName(this).isEmpty()) {
            startActivity(Intent(this, AccountActivity::class.java))
            return
        }
        val input = EditText(this).apply {
            hint = "Code, like K7M2PW"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        AlertDialog.Builder(this)
            .setTitle("Join a job")
            .setView(input)
            .setPositiveButton("Join") { _, _ ->
                val code = input.text.toString().trim().uppercase(Locale.US)
                if (code.isEmpty()) return@setPositiveButton
                Sync.join(this, code) { jobName, zone ->
                    if (jobName == null) {
                        AlertDialog.Builder(this)
                            .setMessage("No job found for \"$code\" — check the code and your signal.")
                            .setPositiveButton("OK", null).show()
                        return@join
                    }
                    val state = LoadStore.read(this)
                    val job = LoadStore.newJob(jobName)
                    job.put("share", code)
                    if (zone != null) job.put("zone", zone)
                    val jobs = org.json.JSONArray().put(job)
                    val existing = LoadStore.jobs(state)
                    for (i in 0 until existing.length()) jobs.put(existing.getJSONObject(i))
                    state.put("jobs", jobs).put("active", job.getString("id"))
                    LoadStore.write(this, state)
                    expanded.clear()
                    GeofenceManager.sync(this)
                    LoadWidget.refresh(this)
                    attachCrewListeners()
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- who's driving (truck-phone mode) -----------------------------------

    private var driverPromptShown = false

    /** Truck phones ask once per day: get in, tap your name, go. */
    private fun maybePromptDriver() {
        if (!Sync.truckMode(this) || driverPromptShown) return
        if (Sync.driverPickedToday(this)) return
        val code = LoadStore.activeJob(LoadStore.read(this)).optString("share")
        if (code.isEmpty() || !Sync.configured(this)) return
        driverPromptShown = true
        showDriverPicker()
    }

    private fun showDriverPicker() {
        val code = LoadStore.activeJob(LoadStore.read(this)).optString("share")
        if (code.isEmpty() || !Sync.configured(this)) {
            AlertDialog.Builder(this)
                .setMessage("Driver pick-lists work on shared jobs — share or join one first.")
                .setPositiveButton("OK", null).show()
            return
        }
        Sync.listRoster(this, code) { names ->
            val options = names.toMutableList()
            options.add("➕  New name…")
            AlertDialog.Builder(this)
                .setTitle("Who's driving?")
                .setItems(options.toTypedArray()) { _, which ->
                    if (which == options.size - 1) newDriverDialog()
                    else {
                        Sync.setDriver(this, options[which])
                        render()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun newDriverDialog() {
        val input = EditText(this).apply {
            hint = "Driver's name"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        AlertDialog.Builder(this)
            .setTitle("Who's driving?")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { Sync.setDriver(this, name); render() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The boss view: everyone's loads today, tallied per driver, live. */
    private fun showCrewToday() {
        val code = LoadStore.activeJob(LoadStore.read(this)).optString("share")
        if (code.isEmpty() || !Sync.configured(this)) {
            AlertDialog.Builder(this)
                .setMessage("Crew totals work on shared jobs — share or join one first.")
                .setPositiveButton("OK", null).show()
            return
        }
        Sync.crewToday(this, code) { counts, total ->
            val lines = StringBuilder()
            if (counts.isEmpty()) lines.append("No shared loads yet today.")
            else {
                for ((who, n) in counts) lines.append(String.format("%-4d %s%n", n, who))
                lines.append("\nTotal: $total loads")
            }
            val body = TextView(this).apply {
                text = lines.toString()
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 16f
                setTextColor(getColor(R.color.ink))
                setPadding(dp(20), dp(12), dp(20), dp(4))
            }
            AlertDialog.Builder(this)
                .setTitle("Crew today · job $code")
                .setView(body)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun attachCrewListeners() {
        detachCrewListeners()
        val code = LoadStore.activeJob(LoadStore.read(this)).optString("share")
        if (code.isEmpty() || !Sync.configured(this)) return
        posListening = Sync.listenPositions(this, code) { drivers ->
            crewDrivers = drivers
            renderCrew(LoadStore.activeJob(LoadStore.read(this)))
            if (dimShown) updateDimCrew()
        }
        zoneListening = Sync.listenZone(this, code) { remote -> applyRemoteZone(remote) }
    }

    private fun detachCrewListeners() {
        posListening?.stop(); posListening = null
        zoneListening?.stop(); zoneListening = null
        crewDrivers = emptyList()
    }

    /** A crew member moved the dump spot — take it, keeping our own auto flag. */
    private fun applyRemoteZone(remote: JSONObject?) {
        val state = LoadStore.read(this)
        val job = LoadStore.activeJob(state)
        if (job.optString("share").isEmpty()) return
        val local = LoadStore.zone(job)
        if (remote == null) {
            if (local == null) return
            job.remove("zone")
        } else {
            if (local != null &&
                local.optDouble("lat") == remote.optDouble("lat") &&
                local.optDouble("lng") == remote.optDouble("lng") &&
                local.optInt("r") == remote.optInt("r")
            ) return
            remote.put("auto", local?.optBoolean("auto") ?: false)
            job.put("zone", remote)
        }
        LoadStore.write(this, state)
        GeofenceManager.sync(this)
        render()
    }

    // ---- dim-mode crew radar ------------------------------------------------

    private fun updateDimCrew() {
        dimCrew.removeAllViews()
        val code = LoadStore.activeJob(LoadStore.read(this)).optString("share")
        if (code.isEmpty() || crewDrivers.isEmpty()) return
        val lat = lastOwnLat
        val lng = lastOwnLng
        val sorted = if (lat != null && lng != null) {
            crewDrivers.sortedBy { AutoCounter.metresBetween(lat, lng, it.lat, it.lng) }
        } else crewDrivers
        for (driver in sorted.take(3)) {
            var text = driver.label().uppercase(Locale.getDefault())
            if (lat != null && lng != null) {
                val meters = AutoCounter.metresBetween(lat, lng, driver.lat, driver.lng)
                text += " · " + fmtMiles(meters) + " " + Sync.compass(lat, lng, driver.lat, driver.lng)
            }
            dimCrew.addView(TextView(this).apply {
                this.text = text
                setTextColor(getColor(R.color.dim_clock))
                textSize = 17f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, dp(3), 0, dp(3))
            })
        }
    }

    private fun fmtMiles(m: Double): String =
        if (m < 305) "${(m * 3.28084).roundToInt()} FT" else String.format("%.1f MI", m / 1609.34)

    /** Freshest own position for radar distances: the watcher's fix, else ask. */
    @SuppressLint("MissingPermission")
    private fun refreshOwnFixForDim() {
        val fix = getSharedPreferences("last_fix", Context.MODE_PRIVATE)
        val t = fix.getLong("t", 0L)
        if (System.currentTimeMillis() - t < 3 * 60_000L) {
            lastOwnLat = fix.getString("lat", null)?.toDoubleOrNull()
            lastOwnLng = fix.getString("lng", null)?.toDoubleOrNull()
            updateDimCrew()
            return
        }
        if (!GeofenceManager.hasForegroundLocation(this)) return
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { loc ->
                loc ?: return@addOnSuccessListener
                lastOwnLat = loc.latitude
                lastOwnLng = loc.longitude
                Sync.publishPosition(this, loc.latitude, loc.longitude)
                updateDimCrew()
            }
    }

    private fun renderHistory(job: JSONObject) {
        histList.removeAllViews()
        val keys = LoadStore.dayKeys(job)
        val today = LoadStore.todayKey()
        val yesterday = LoadStore.yesterdayKey()
        val jobId = job.optString("id")

        if (keys.isEmpty()) {
            histList.addView(muted("No loads yet — hit the big button."))
            return
        }

        for (key in keys) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_row)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }

            val labelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val isOpen = expanded.contains(key)
            val title = TextView(this).apply {
                text = niceDay(key) + if (isOpen) "  ▾" else "  ▸"
                setTextColor(getColor(R.color.ink))
                textSize = 14f
            }
            labelBox.addView(title)
            val tag = when (key) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> null
            }
            if (tag != null) {
                labelBox.addView(TextView(this).apply {
                    text = tag
                    setTextColor(getColor(R.color.muted))
                    textSize = 11f
                })
            }
            labelBox.setOnClickListener {
                if (isOpen) expanded.remove(key) else expanded.add(key)
                render()
            }
            row.addView(labelBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(TextView(this).apply {
                text = LoadStore.dayCount(job, key).toString()
                setTextColor(getColor(R.color.ink))
                textSize = 20f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })

            row.addView(miniButton("−") {
                LoadStore.adjustDay(this, jobId, key, -1)
                LoadWidget.refresh(this)
                render()
            })
            row.addView(miniButton("+") {
                LoadStore.adjustDay(this, jobId, key, +1)
                LoadWidget.refresh(this)
                render()
            })

            histList.addView(row, rowLp)

            if (isOpen) renderDayDetail(job, key)
        }
    }

    private fun renderDayDetail(job: JSONObject, key: String) {
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(6))
        }
        val loads = LoadStore.loadsFor(job, key)
        if (loads.length() == 0) {
            detail.addView(muted("No stamped loads this day (count was set by hand)"))
        }
        for (i in 0 until loads.length()) {
            val load = loads.getJSONObject(i)
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            line.addView(TextView(this).apply {
                text = "#" + (i + 1)
                setTextColor(getColor(R.color.muted))
                textSize = 13f
                width = dp(38)
            })
            line.addView(TextView(this).apply {
                text = fmtTime(load.optLong("t"))
                setTextColor(getColor(R.color.ink))
                textSize = 15f
                typeface = android.graphics.Typeface.MONOSPACE
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (load.optBoolean("auto")) {
                line.addView(TextView(this).apply {
                    text = "AUTO"
                    setTextColor(getColor(R.color.good))
                    textSize = 10f
                    setPadding(dp(5), dp(3), dp(5), dp(3))
                })
            }

            if (load.has("lat") && load.has("lng")) {
                val lat = load.optDouble("lat")
                val lng = load.optDouble("lng")
                line.addView(Button(this).apply {
                    text = "MAP"
                    isAllCaps = true
                    textSize = 12f
                    setTextColor(getColor(R.color.accent))
                    background = getDrawable(R.drawable.bg_chip_on)
                    minWidth = 0
                    minHeight = dp(38)
                    setPadding(dp(10), 0, dp(10), 0)
                    stateListAnimator = null
                    setOnClickListener { openMap(lat, lng) }
                })
            } else {
                line.addView(TextView(this).apply {
                    text = "no location"
                    setTextColor(getColor(R.color.muted))
                    textSize = 12f
                })
            }
            detail.addView(line)
        }
        histList.addView(detail)
    }

    private fun renderZone(job: JSONObject) {
        val zone = LoadStore.zone(job)
        val on = zone != null && zone.optBoolean("auto")
        autoBtn.text = if (on) getString(R.string.auto_on) else getString(R.string.auto_off)
        autoBtn.background = getDrawable(if (on) R.drawable.bg_btn_on else R.drawable.bg_btn)
        autoBtn.setTextColor(getColor(if (on) R.color.good else R.color.ink))

        zoneSetBtn.text = getString(if (zone == null) R.string.set_zone else R.string.move_zone)
        zoneClearBtn.visibility = if (zone == null) View.GONE else View.VISIBLE

        // radius chips
        radiusRow.removeAllViews()
        val current = zone?.optInt("r") ?: pendingRadius
        for (r in radii) {
            val selected = r.meters == current
            val chip = Button(this).apply {
                text = r.label
                isAllCaps = false
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(getColor(if (selected) R.color.accent else R.color.ink))
                background = getDrawable(if (selected) R.drawable.bg_chip_on else R.drawable.bg_btn)
                minWidth = 0
                minHeight = dp(42)
                stateListAnimator = null
                setPadding(dp(6), 0, dp(6), 0)
                setOnClickListener {
                    if (zone != null) {
                        LoadStore.setZoneRadius(this@MainActivity, r.meters)
                        Sync.publishZone(this@MainActivity)
                    } else pendingRadius = r.meters
                    GeofenceManager.sync(this@MainActivity)
                    render()
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (radiusRow.childCount > 0) lp.marginStart = dp(6)
            radiusRow.addView(chip, lp)
        }

        // Say plainly what the geofence is doing; silent failure was the whole
        // problem before. status() is written by every registration attempt.
        val needsPermission = zone != null && on && !GeofenceManager.hasBackgroundLocation(this)
        permBtn.visibility = if (needsPermission) View.VISIBLE else View.GONE

        zoneInfo.text = when {
            zone == null ->
                "No zone yet — park where you dump, pick a size, and set it. " +
                    "Every drive into the circle then counts a load, even with the screen off."
            !on -> radiusLabel(zone.optInt("r")) + " circle · auto counting is off"
            needsPermission ->
                radiusLabel(zone.optInt("r")) + " circle · NOT COUNTING — Android needs " +
                    "location set to \"Allow all the time\" for this app. Tap the button below."
            else -> {
                val last = AutoCounter.lastCount(this)
                val lastLine = if (last > 0L) "\nLast auto count: " + fmtTime(last)
                    else "\nNo auto count yet."
                val battery = batteryWarning()
                radiusLabel(zone.optInt("r")) + " circle · watching (see the " +
                    "notification for live distance)" + lastLine + battery
            }
        }
    }

    private fun batteryWarning(): String {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return ""
        return if (pm.isIgnoringBatteryOptimizations(packageName)) ""
        else "\n⚠ Battery saver may stop this — tap AUTO ON again to allow unrestricted running."
    }

    // ---- zone actions -------------------------------------------------------

    private fun toggleAuto() {
        val state = LoadStore.read(this)
        val job = LoadStore.activeJob(state)
        val zone = LoadStore.zone(job)
        if (zone == null) {
            zoneInfo.text = "Set a zone first — park at the pit and tap SET ZONE AT MY SPOT."
            return
        }
        val turningOn = !zone.optBoolean("auto")
        LoadStore.setAuto(this, turningOn)
        if (turningOn) {
            // Assume you're at the pit when you switch it on, so the first
            // count only happens after you've actually left and come back.
            AutoCounter.setArmed(this, false)
            if (!GeofenceManager.hasBackgroundLocation(this)) {
                requestLocation()
            } else {
                GeofenceManager.sync(this)
            }
            ZoneWatchService.start(this)
            askBatteryExemption()
        } else {
            GeofenceManager.sync(this)
            ZoneWatchService.stop(this)
        }
        render()
    }

    /**
     * Phone makers' battery savers are the usual reason background counting
     * dies after a while. Ask once to be left alone.
     */
    private fun askBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("Keep counting in the background")
            .setMessage(
                "Android's battery saver can put this app to sleep, which stops loads " +
                    "being counted at the dump zone.\n\nAllowing it to run unrestricted is " +
                    "what keeps auto counting reliable through the day."
            )
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    openAppLocationSettings()
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun onSetZoneHere() {
        if (!GeofenceManager.hasForegroundLocation(this)) {
            pendingRadiusOnly = true
            requestLocation()
            return
        }
        captureZoneHere()
    }

    @SuppressLint("MissingPermission")
    private fun captureZoneHere() {
        zoneInfo.text = "Getting your position…"
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    zoneInfo.text = "Couldn't get GPS. Try again with a clear view of the sky."
                    return@addOnSuccessListener
                }
                LoadStore.setZone(this, loc.latitude, loc.longitude, currentRadius())
                GeofenceManager.sync(this)
                Sync.publishZone(this)
                render()
            }
            .addOnFailureListener {
                zoneInfo.text = "Couldn't get GPS. Check location permission and try again."
            }
    }

    private fun onSetZoneFromText() {
        val parsed = parseLatLng(coordInput.text.toString())
        if (parsed == null) {
            zoneInfo.text = "Couldn't read those coordinates. Try: 43.61234, -96.54321"
            return
        }
        LoadStore.setZone(this, parsed.first, parsed.second, currentRadius())
        coordInput.setText("")
        GeofenceManager.sync(this)
        Sync.publishZone(this)
        if (!GeofenceManager.hasBackgroundLocation(this)) requestLocation()
        render()
    }

    private fun currentRadius(): Int {
        val zone = LoadStore.zone(LoadStore.activeJob(LoadStore.read(this)))
        return zone?.optInt("r") ?: pendingRadius
    }

    private fun requestLocation() {
        if (!GeofenceManager.hasForegroundLocation(this)) {
            askFineLocation.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else {
            askBackgroundIfNeeded()
        }
    }

    /**
     * "Allow all the time" is asked for separately from normal location access.
     * From Android 11 it cannot be granted from an in-app prompt at all — the
     * only route is the app's settings page — so send the user straight there.
     */
    private fun askBackgroundIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || GeofenceManager.hasBackgroundLocation(this)) {
            GeofenceManager.sync(this)
            render()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Count loads with the screen off")
            .setMessage(
                "To count a load when you drive into the dump zone with the app closed, " +
                    "Android needs this app's location set to \"Allow all the time\".\n\n" +
                    "Android only allows that from Settings:\n\n" +
                    "Permissions → Location → Allow all the time"
            )
            .setPositiveButton("Open settings") { _, _ -> openAppLocationSettings() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun openAppLocationSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    // ---- jobs ---------------------------------------------------------------

    private fun showNewJobDialog() {
        val input = EditText(this).apply {
            hint = "Job name (field, farm, crop…)"
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        AlertDialog.Builder(this)
            .setTitle("Start a new job")
            .setView(input)
            .setPositiveButton("Start tracking") { _, _ ->
                val state = LoadStore.read(this)
                val typed = input.text.toString().trim()
                val name = if (typed.isEmpty()) "Job " + (LoadStore.jobs(state).length() + 1) else typed
                val job = LoadStore.newJob(name)
                // newest first, and make it active
                val jobs = org.json.JSONArray().put(job)
                val existing = LoadStore.jobs(state)
                for (i in 0 until existing.length()) jobs.put(existing.getJSONObject(i))
                state.put("jobs", jobs).put("active", job.getString("id"))
                LoadStore.write(this, state)
                expanded.clear()
                GeofenceManager.sync(this)
                LoadWidget.refresh(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showJobsDialog() {
        val state = LoadStore.read(this)
        val jobs = LoadStore.jobs(state)
        val active = LoadStore.activeJob(state).optString("id")
        val names = ArrayList<String>()
        val ids = ArrayList<String>()
        var checked = 0
        for (i in 0 until jobs.length()) {
            val job = jobs.getJSONObject(i)
            ids.add(job.optString("id"))
            names.add(job.optString("name") + "   (" + LoadStore.jobTotal(job) + ")")
            if (job.optString("id") == active) checked = i
        }
        AlertDialog.Builder(this)
            .setTitle("Jobs")
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                val fresh = LoadStore.read(this)
                fresh.put("active", ids[which])
                LoadStore.write(this, fresh)
                expanded.clear()
                GeofenceManager.sync(this)
                LoadWidget.refresh(this)
                render()
                dialog.dismiss()
            }
            .setPositiveButton("Rename") { _, _ -> showRenameDialog() }
            .setNegativeButton("Delete this job") { _, _ -> confirmDelete() }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun showRenameDialog() {
        val state = LoadStore.read(this)
        val job = LoadStore.activeJob(state)
        val input = EditText(this).apply {
            setText(job.optString("name"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        AlertDialog.Builder(this)
            .setTitle("Rename job")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val fresh = LoadStore.read(this)
                    LoadStore.activeJob(fresh).put("name", name)
                    LoadStore.write(this, fresh)
                    LoadWidget.refresh(this)
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete() {
        val job = LoadStore.activeJob(LoadStore.read(this))
        AlertDialog.Builder(this)
            .setTitle("Delete \"" + job.optString("name") + "\"?")
            .setMessage("Its loads and history are removed from this phone. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val state = LoadStore.read(this)
                val jobs = LoadStore.jobs(state)
                val keep = org.json.JSONArray()
                val doomed = LoadStore.activeJob(state).optString("id")
                for (i in 0 until jobs.length()) {
                    val j = jobs.getJSONObject(i)
                    if (j.optString("id") != doomed) keep.put(j)
                }
                if (keep.length() == 0) keep.put(LoadStore.newJob("Job 1"))
                state.put("jobs", keep).put("active", keep.getJSONObject(0).getString("id"))
                LoadStore.write(this, state)
                expanded.clear()
                GeofenceManager.sync(this)
                LoadWidget.refresh(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- screen: keep awake + auto dim --------------------------------------

    private fun applyAwake() {
        if (awakeWanted) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        paintToggles()
    }

    private fun paintToggles() {
        awakeBtn.text = getString(if (awakeWanted) R.string.screen_staying_on else R.string.keep_screen_on)
        awakeBtn.background = getDrawable(if (awakeWanted) R.drawable.bg_btn_on else R.drawable.bg_btn)
        awakeBtn.setTextColor(getColor(if (awakeWanted) R.color.good else R.color.muted))

        dimBtn.text = getString(if (dimWanted) R.string.dim_in_10 else R.string.auto_dim)
        dimBtn.background = getDrawable(if (dimWanted) R.drawable.bg_btn_on else R.drawable.bg_btn)
        dimBtn.setTextColor(getColor(if (dimWanted) R.color.good else R.color.muted))
    }

    private fun armDim() {
        ui.removeCallbacks(dimTick)
        if (dimShown) hideDim()
        if (dimWanted) ui.postDelayed(dimTick, 10_000L)
    }

    private fun showDim() {
        if (!dimWanted) return
        val job = LoadStore.activeJob(LoadStore.read(this))
        dimCount.text = LoadStore.dayCount(job, LoadStore.todayKey()).toString()
        tickClock()
        dimOverlay.visibility = View.VISIBLE
        dimShown = true
        if (job.optString("share").isNotEmpty()) {
            updateDimCrew()
            ui.removeCallbacks(dimCrewTick)
            dimCrewTick.run()
        }
    }

    private fun hideDim() {
        dimOverlay.visibility = View.GONE
        ui.removeCallbacks(dimCrewTick)
        if (dimShown) {
            dimShown = false
            ui.removeCallbacks(dimTick)
            if (dimWanted) ui.postDelayed(dimTick, 10_000L)
        }
    }

    private fun tickClock() {
        val now = java.time.LocalDateTime.now()
        clockTime.text = clockFmt.format(now) + " " + ampmFmt.format(now)
        clockDate.text = dateFmt.format(now)
        dimClock.text = clockFmt.format(now) + " " + ampmFmt.format(now)
        // a load counted in the background should appear without a manual refresh
        render()
    }

    // ---- small helpers ------------------------------------------------------

    private fun miniButton(label: String, onTap: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 18f
        b.setTextColor(getColor(R.color.ink))
        b.background = getDrawable(R.drawable.bg_btn)
        b.minWidth = dp(44)
        b.minHeight = dp(44)
        b.width = dp(44)
        b.stateListAnimator = null
        b.setPadding(0, 0, 0, 0)
        b.setOnClickListener { onTap() }
        val lp = LinearLayout.LayoutParams(dp(44), dp(44))
        lp.marginStart = dp(8)
        b.layoutParams = lp
        return b
    }

    private fun muted(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.muted))
        textSize = 13f
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun openMap(lat: Double, lng: Double) {
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(Load)")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lng")))
        }
    }

    private fun buzz(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms)
        }
    }

    private fun fmtTime(millis: Long): String =
        timeFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun niceDay(key: String): String = try {
        dayFmt.format(LocalDate.parse(key))
    } catch (_: Exception) {
        key
    }

    private fun radiusLabel(meters: Int): String =
        radii.firstOrNull { it.meters == meters }?.label
            ?: ((meters * 3.28084).roundToInt().toString() + " FT")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    /** Accepts "43.6, -96.5" and "43.6° N, 96.5° W". */
    private fun parseLatLng(raw: String): Pair<Double, Double>? {
        if (TextUtils.isEmpty(raw)) return null
        val text = raw.trim().uppercase(Locale.US)
        val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(text).map { it.value.toDouble() }.toList()
        if (numbers.size < 2) return null
        var lat = numbers[0]
        var lng = numbers[1]
        if (text.contains("S") && lat > 0) lat = -lat
        if (text.contains("W") && lng > 0) lng = -lng
        if (abs(lat) > 90 || abs(lng) > 180) return null
        return Pair(lat, lng)
    }
}
