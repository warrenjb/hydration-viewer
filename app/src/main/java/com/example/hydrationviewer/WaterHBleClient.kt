package com.example.hydrationviewer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

data class WaterHDrinkRecord(
    val dateTime: LocalDateTime,
    val volumeMl: Int,
)

/**
 * Talks to a WaterH bottle directly over Bluetooth Low Energy, bypassing WaterH's
 * own (soon-to-be-shut-down) Google Fit cloud integration entirely.
 *
 * The record format below was reverse-engineered from real captured notifications
 * on characteristic 0xFFE4 (service 0xFFE0), cross-checked against the WaterH app's
 * own displayed totals — see the conversation this was built from for the raw bytes.
 * Each 13-byte record is: date(3) + time(3) + unknown(1) + volumeMl(1) + tail(5).
 * On connect, the bottle sends a batch of history records wrapped as
 * [length][type][record][record]...; live single drinks arrive wrapped as
 * [6-byte header][record]. This is inferred from a handful of samples, not a
 * spec — treat parsing as best-effort and sanity-check volumes before trusting them.
 */
class WaterHBleClient(private val context: Context) {

    companion object {
        private const val TAG = "WaterHBleClient"
        private const val DEVICE_NAME_PREFIX = "WATERH"

        private val SERVICE_UUID = uuidFrom16Bit(0xFFE0)
        private val CHARACTERISTIC_UUID = uuidFrom16Bit(0xFFE4)
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun uuidFrom16Bit(shortUuid: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", shortUuid))
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var gatt: BluetoothGatt? = null

    /**
     * Scans for a nearby WaterH bottle, connects, subscribes to its drink-log
     * characteristic, and reports every record parsed. [onRecords] may be called
     * multiple times (once per notification received) — the caller should
     * de-duplicate by timestamp when writing to storage. [onStatus] reports
     * human-readable progress for the UI. Call [stop] when done or on error.
     */
    @SuppressLint("MissingPermission") // Permission is checked by the caller before calling this.
    fun connectAndListen(
        onStatus: (String) -> Unit,
        onRecords: (List<WaterHDrinkRecord>) -> Unit,
    ) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth is off or unavailable.")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onStatus("BLE scanning unavailable on this device.")
            return
        }

        onStatus("Scanning for WaterH bottle…")

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (!name.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true)) return

                scanner.stopScan(this)
                onStatus("Found $name — connecting…")
                connectTo(result.device, onStatus, onRecords)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                onStatus("Scan failed (code $errorCode).")
            }
        }

        scanner.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(
        device: BluetoothDevice,
        onStatus: (String) -> Unit,
        onRecords: (List<WaterHDrinkRecord>) -> Unit,
    ) {
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    onStatus("Connected. Discovering services…")
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    onStatus("Disconnected.")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val characteristic = g.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    onStatus("Couldn't find the expected drink-log characteristic.")
                    return
                }
                g.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Android 13+: the old setValue()+writeDescriptor(descriptor) path is
                        // deprecated and can silently no-op on some builds — this is the
                        // real way to enable notifications now.
                        g.writeDescriptor(descriptor, value)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = value
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(descriptor)
                    }
                } else {
                    onStatus("No notification descriptor found — can't subscribe.")
                    return
                }
                onStatus("Listening for drink data…")
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                val ok = status == BluetoothGatt.GATT_SUCCESS
                Log.d(TAG, "Descriptor write ${if (ok) "succeeded" else "FAILED (status=$status)"}")
                if (!ok) onStatus("Subscribing to notifications failed (status $status).")
            }

            // Pre-Android 13 callback (still invoked on many devices/OS builds).
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                handleIncoming(characteristic.value, onRecords)
            }

            // Android 13+ callback.
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleIncoming(value, onRecords)
            }
        })
    }

    private fun handleIncoming(data: ByteArray?, onRecords: (List<WaterHDrinkRecord>) -> Unit) {
        if (data == null) return
        Log.d(TAG, "Raw bytes: " + data.joinToString("-") { String.format("%02X", it) })
        val records = parseRecords(data)
        if (records.isNotEmpty()) onRecords(records)
    }

    /**
     * Exposed for testing/inspection independent of a live BLE connection.
     *
     * The bottle uses more than one framing scheme for the same underlying 13-byte
     * records — a 2-byte [length][type] header where length == data.size - 1, and a
     * 6-byte header seen on both single-drink and multi-record batch pushes. Rather
     * than hardcode each shape we've happened to see, this detects the header length
     * generically: whichever candidate leaves a remainder that's an exact multiple
     * of the 13-byte record size is used. New packet shapes may still show up as
     * real-world data comes in — treat "Unrecognized packet shape" in Logcat as a
     * signal to capture that packet and check whether a new header length is needed.
     */
    fun parseRecords(data: ByteArray): List<WaterHDrinkRecord> {
        val recordSize = 13
        val headerCandidates = mutableListOf<Int>()

        // Most specific first: explicit length-prefixed framing.
        if (data.isNotEmpty() && (data[0].toInt() and 0xFF) == data.size - 1) {
            headerCandidates.add(2)
        }
        // General 6-byte header seen on both single and multi-record pushes.
        headerCandidates.add(6)

        for (headerLen in headerCandidates) {
            val remaining = data.size - headerLen
            if (remaining > 0 && remaining % recordSize == 0) {
                return (0 until remaining / recordSize).mapNotNull { i ->
                    parseOneRecord(data, headerLen + i * recordSize)
                }
            }
        }

        Log.w(TAG, "Unrecognized packet shape, size=${data.size}: " +
            data.joinToString("-") { String.format("%02X", it) })
        return emptyList()
    }

    private fun parseOneRecord(data: ByteArray, offset: Int): WaterHDrinkRecord? {
        if (offset + 8 > data.size) return null
        fun byte(i: Int) = data[offset + i].toInt() and 0xFF

        val year = 2000 + byte(0)
        val month = byte(1)
        val day = byte(2)
        val hour = byte(3)
        val minute = byte(4)
        val second = byte(5)
        val volumeMl = byte(7)

        return try {
            WaterHDrinkRecord(
                dateTime = LocalDateTime.of(year, month, day, hour, minute, second),
                volumeMl = volumeMl,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skipping record with implausible date/time", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
