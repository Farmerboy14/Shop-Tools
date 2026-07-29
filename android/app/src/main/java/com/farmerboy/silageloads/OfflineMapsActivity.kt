package com.farmerboy.silageloads

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/**
 * Pick which states to keep on the phone. One file per state; while a
 * download runs, progress shows here and in the notification shade.
 */
class OfflineMapsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            OfflineMaps.refresh(this@OfflineMapsActivity)
            render()
            ui.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_maps)
        list = findViewById(R.id.stateList)
        findViewById<Button>(R.id.offlineCloseBtn).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun render() {
        list.removeAllViews()
        for (state in OfflineMaps.STATES) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_row)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }

            val pending = OfflineMaps.pendingId(this, state) >= 0
            val done = OfflineMaps.isDone(this, state)

            val labelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labelBox.addView(TextView(this).apply {
                text = state.label
                setTextColor(getColor(R.color.ink))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            val status = when {
                pending -> {
                    val p = OfflineMaps.progress(this@OfflineMapsActivity, state)
                    if (p == null) "starting…"
                    else if (p.second > 0)
                        "downloading… " + OfflineMaps.fmtBytes(p.first) + " of " + OfflineMaps.fmtBytes(p.second)
                    else "downloading… " + OfflineMaps.fmtBytes(p.first)
                }
                done -> "on this phone · " + OfflineMaps.fmtBytes(OfflineMaps.fileFor(this, state).length())
                else -> "not downloaded"
            }
            labelBox.addView(TextView(this).apply {
                text = status
                setTextColor(getColor(if (done) R.color.good else R.color.muted))
                textSize = 12f
            })
            row.addView(labelBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val button = Button(this).apply {
                isAllCaps = true
                textSize = 12f
                minWidth = dp(88)
                minHeight = dp(42)
                stateListAnimator = null
                when {
                    pending -> {
                        text = getString(R.string.cancel)
                        background = getDrawable(R.drawable.bg_btn)
                        setTextColor(getColor(R.color.warn))
                        setOnClickListener { OfflineMaps.cancel(this@OfflineMapsActivity, state); render() }
                    }
                    done -> {
                        text = getString(R.string.delete)
                        background = getDrawable(R.drawable.bg_btn)
                        setTextColor(getColor(R.color.muted))
                        setOnClickListener { confirmDelete(state) }
                    }
                    else -> {
                        text = getString(R.string.get_map)
                        background = getDrawable(R.drawable.bg_chip_on)
                        setTextColor(getColor(R.color.accent))
                        setOnClickListener { confirmDownload(state) }
                    }
                }
            }
            row.addView(button)
            list.addView(row, rowLp)
        }
    }

    private fun confirmDownload(state: OfflineMaps.State) {
        AlertDialog.Builder(this)
            .setTitle("Download " + state.label + "?")
            .setMessage(
                "One file covers the whole state at full detail. Small states are " +
                    "tens of MB; big ones can be several hundred. Wi-Fi is a good idea.\n\n" +
                    "It downloads in the background — progress shows here and in the " +
                    "notification shade."
            )
            .setPositiveButton("Download") { _, _ ->
                OfflineMaps.startDownload(this, state)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(state: OfflineMaps.State) {
        AlertDialog.Builder(this)
            .setTitle("Delete the " + state.label + " map?")
            .setMessage("Frees the space. You can download it again any time.")
            .setPositiveButton("Delete") { _, _ ->
                OfflineMaps.delete(this, state)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
