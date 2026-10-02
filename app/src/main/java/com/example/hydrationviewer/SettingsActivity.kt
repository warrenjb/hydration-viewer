package com.example.hydrationviewer

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** 1 fl oz = 29.5735 mL. */
private const val ML_PER_OUNCE = 29.5735

private const val TAG = "HydrationViewerSettings"

/**
 * Everything that isn't the day-to-day chart/total view: granting Health
 * Connect access and connecting each bottle to sync it. Split out from
 * MainActivity so the main screen can give charts and the entry list the
 * whole landscape instead of sharing it with these buttons and status lines.
 *
 * Syncing writes straight into Health Connect, so nothing needs to be passed
 * back to MainActivity explicitly — it just re-reads on resume.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var reader: HydrationReader
    private lateinit var bleClient: WaterHBleClient
    private lateinit var thermosBleClient: ThermosBleClient
    private lateinit var healthConnectStatusText: TextView
    private lateinit var waterHStatusText: TextView
    private lateinit var thermosStatusText: TextView
    private lateinit var manualStatusText: TextView

    private var pendingSyncTarget: SyncTarget = SyncTarget.WATERH

    private enum class SyncTarget { WATERH, THERMOS }

    private val requestHealthConnectPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        Log.d(TAG, "Health Connect permission result: $granted")
        lifecycleScope.launch { updateHealthConnectStatus() }
    }

    private val requestBluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            when (pendingSyncTarget) {
                SyncTarget.WATERH -> startWaterHSync()
                SyncTarget.THERMOS -> startThermosSync()
            }
        } else {
            statusTextFor(pendingSyncTarget).text = "Bluetooth permission denied."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings"
        reader = HydrationReader(this)
        bleClient = WaterHBleClient(this)
        thermosBleClient = ThermosBleClient(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val healthConnectLabel = TextView(this).apply {
            text = "Health Connect"
            textSize = 18f
            setPadding(0, 0, 0, 8)
        }
        healthConnectStatusText = TextView(this).apply { textSize = 14f }
        val grantHealthConnectButton = Button(this).apply {
            text = "Grant Health Connect permission"
            setOnClickListener {
                val status = HealthConnectClient.getSdkStatus(this@SettingsActivity)
                Log.d(TAG, "Health Connect SDK status: $status")
                requestHealthConnectPermissions.launch(HydrationReader.REQUIRED_PERMISSIONS)
            }
        }

        val waterHLabel = TextView(this).apply {
            text = "WaterH bottle"
            textSize = 18f
            setPadding(0, 48, 0, 8)
        }
        val syncWaterHButton = Button(this).apply {
            text = "Connect WaterH Bottle & Sync"
            setOnClickListener {
                pendingSyncTarget = SyncTarget.WATERH
                requestBluetoothPermissionsThenSync()
            }
        }
        waterHStatusText = TextView(this).apply { textSize = 14f }

        val thermosLabel = TextView(this).apply {
            text = "Thermos bottle"
            textSize = 18f
            setPadding(0, 48, 0, 8)
        }
        val syncThermosButton = Button(this).apply {
            text = "Connect Thermos Bottle & Sync"
            setOnClickListener {
                pendingSyncTarget = SyncTarget.THERMOS
                requestBluetoothPermissionsThenSync()
            }
        }
        thermosStatusText = TextView(this).apply { textSize = 14f }

        val manualLabel = TextView(this).apply {
            text = "Manual entry"
            textSize = 18f
            setPadding(0, 48, 0, 8)
        }
        val addManualButton = Button(this).apply {
            text = "Add Manual Entry"
            setOnClickListener { promptForManualEntry() }
        }
        manualStatusText = TextView(this).apply { textSize = 14f }

        root.addView(healthConnectLabel)
        root.addView(grantHealthConnectButton)
        root.addView(healthConnectStatusText)
        root.addView(waterHLabel)
        root.addView(syncWaterHButton)
        root.addView(waterHStatusText)
        root.addView(thermosLabel)
        root.addView(syncThermosButton)
        root.addView(thermosStatusText)
        root.addView(manualLabel)
        root.addView(addManualButton)
        root.addView(manualStatusText)
        setContentView(root)

        lifecycleScope.launch { updateHealthConnectStatus() }
    }

    private fun statusTextFor(target: SyncTarget): TextView = when (target) {
        SyncTarget.WATERH -> waterHStatusText
        SyncTarget.THERMOS -> thermosStatusText
    }

    private fun requestBluetoothPermissionsThenSync() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestBluetoothPermissions.launch(needed)
    }

    private fun startWaterHSync() {
        lifecycleScope.launch {
            if (!reader.hasPermission()) {
                waterHStatusText.text = "Grant Health Connect permission first, so we have somewhere to write WaterH's data."
                return@launch
            }
            bleClient.connectAndListen(
                onStatus = { status ->
                    runOnUiThread { waterHStatusText.text = status }
                },
                onRecords = { records ->
                    lifecycleScope.launch {
                        reader.writeWaterHRecords(records)
                        waterHStatusText.text = "Synced ${records.size} WaterH record(s)."
                    }
                },
            )
        }
    }

    private fun startThermosSync() {
        lifecycleScope.launch {
            if (!reader.hasPermission()) {
                thermosStatusText.text = "Grant Health Connect permission first, so we have somewhere to write Thermos's data."
                return@launch
            }
            thermosBleClient.connectAndListen(
                onStatus = { status ->
                    runOnUiThread { thermosStatusText.text = status }
                },
                onRecords = { sips ->
                    lifecycleScope.launch {
                        reader.writeThermosRecords(sips)
                        thermosStatusText.text = "Synced ${sips.size} Thermos sip(s)."
                    }
                },
            )
        }
    }

    private fun promptForManualEntry() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Ounces"
        }
        AlertDialog.Builder(this)
            .setTitle("Add Manual Entry")
            .setMessage("How many ounces did you drink?")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val ounces = input.text.toString().toDoubleOrNull()
                if (ounces == null || ounces <= 0.0) {
                    manualStatusText.text = "Enter a number of ounces greater than 0."
                    return@setPositiveButton
                }
                writeManualEntry(ounces)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun writeManualEntry(ounces: Double) {
        lifecycleScope.launch {
            if (!reader.hasPermission()) {
                manualStatusText.text = "Grant Health Connect permission first, so we have somewhere to write this."
                return@launch
            }
            reader.writeManualRecord(ounces * ML_PER_OUNCE)
            manualStatusText.text = "Logged %.1f oz.".format(ounces)
        }
    }

    private suspend fun updateHealthConnectStatus() {
        healthConnectStatusText.text = if (reader.hasPermission()) {
            "Permission granted."
        } else {
            "Not granted yet — tap above."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleClient.stop()
        thermosBleClient.stop()
    }
}
