package com.example.hydrationviewer

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The main screen is charts and data only — granting Health Connect access
 * and connecting/syncing each bottle lives in SettingsActivity, so this
 * screen doesn't have to share landscape with those buttons and status
 * lines. Re-reads from Health Connect every time this screen resumes, which
 * covers coming back from a sync done in Settings.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var reader: HydrationReader
    private lateinit var totalText: TextView
    private lateinit var chartView: BarChartView
    private lateinit var listView: ListView

    private val timeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = HydrationReader(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        totalText = TextView(this).apply {
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val settingsButton = Button(this).apply {
            text = "Settings"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        headerRow.addView(totalText)
        headerRow.addView(settingsButton)

        val chartLabel = TextView(this).apply {
            text = "Last 7 days"
            textSize = 16f
            setPadding(0, 32, 0, 8)
        }
        chartView = BarChartView(this)

        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(headerRow)
        root.addView(chartLabel)
        root.addView(chartView)
        root.addView(listView)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { refresh() }
    }

    private fun friendlySourceName(packageName: String): String = when {
        packageName == "${this.packageName}#waterh" -> "WaterH"
        packageName == "${this.packageName}#thermos" -> "Thermos"
        packageName == "${this.packageName}#manual" -> "Manual"
        packageName == this.packageName -> "WaterH" // legacy entries written before source tagging
        packageName.contains("hidrate", ignoreCase = true) -> "Hidrate Spark"
        else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private suspend fun refresh() {
        if (!reader.hasPermission()) {
            totalText.text = "Grant Health Connect permission in Settings to see your water intake."
            listView.adapter = null
            chartView.setData(emptyList()) { it }
            return
        }

        val total = reader.todayTotal()
        totalText.text = "Today: %.0f mL".format(total.inMilliliters)

        val sourceTotals = reader.dailySourceTotalsForLastDays(7)
        chartView.setData(sourceTotals) { pkg -> friendlySourceName(pkg) }

        val entries = reader.readRecentEntries(days = 7)
        val rows = entries.map { entry ->
            val localTime = entry.time.atZone(ZoneId.systemDefault()).format(timeFormatter)
            "%.0f mL  •  %s  •  %s".format(entry.volume.inMilliliters, localTime, friendlySourceName(entry.sourceApp))
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }
}
