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
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** A single raw fill-level reading off the bottle (history record or live notify). */
data class ThermosLevelReading(val dateTime: LocalDateTime, val fillLevelPercent: Int)

/** A drink inferred from a drop in fill level between two readings. */
data class ThermosSip(val dateTime: LocalDateTime, val volumeMl: Int)

/**
 * Talks to a Thermos Connected Hydration Bottle (Smart Lid) directly over BLE.
 *
 * Getting this working took two rounds of reverse-engineering. The first — a
 * single Bluetooth HCI snoop log of the real Thermos app — looked like it
 * showed a static 16-byte "auth" value written to two characteristics before
 * the bottle would talk. Replaying those exact bytes got rejected outright
 * (GATT status 133 / "Write Not Permitted" on the follow-up command), because
 * a *second* capture revealed those bytes are different every connection —
 * it's a genuine AES-128 challenge-response handshake, not a static token.
 *
 * The actual protocol (service `64ac...`), straight from decompiling the real
 * Thermos app's APK (`com.thermos.smartlid`, class
 * `com.idevicesinc.sweetblue.AuthTransaction`) and verifying it against the
 * second real capture byte-for-byte:
 *
 *  1. Generate 8 random bytes, zero-pad to 16 ("app challenge"), write to
 *     `64ac0002`.
 *  2. Read `64ac0003` ("device challenge") — the bottle returns 16 bytes,
 *     which are `AES-128/ECB/NoPadding` encrypted with a key hardcoded in
 *     the Thermos app itself.
 *  3. Decrypt that with the same key. The first 8 bytes of the plaintext
 *     must equal the random bytes sent in step 1 (proves the bottle knows
 *     the key); if they don't match, authentication failed.
 *  4. Build a 16-byte response: 8 zero bytes followed by the *second* 8
 *     bytes of that decrypted plaintext. Encrypt that with the same key and
 *     write it to `64ac0004`.
 *  5. Only now will the bottle accept writes to `40fc0003` (the command
 *     channel) — service `40fc...`.
 *
 * From there: enable notify on `40fc0001` (live fill level), `40fc0002`
 * (live temperature) and `40fc0005` (battery charging status), indicate on
 * `40fc0004` (history records), then write to `40fc0003` — a far-past
 * "since" epoch, the phone's current time, and a 3-byte trigger
 * (`03 01 <tz>`, where the last byte is the local UTC offset the real app's
 * `Util.getTimeZone()` computes, not a fixed value).
 *
 * That alone does **not** make the bottle start talking, confirmed from the
 * same real capture: the real app sends that exact 00/01/03 sequence once
 * and nothing comes back either, until it writes the "since" command
 * (`00 04 <since>`) a *second* time — only then does the first batch of
 * `40fc0004` indications arrive. After that, each further batch is gated
 * behind a plain notify on `40fc0001` (the bottle's "there's more" signal);
 * the real app answers it by re-sending `00 04 <timestamp of the last
 * record just received>`, which is what pulls the next batch. This repeats
 * until the bottle stops notifying.
 *
 * The 8-byte `40fc0004` records are: a little-endian Unix timestamp, a
 * group id, a sub-index, a fill-level percent (0-100), and a
 * roughly-stable battery-ish byte. There's no explicit "volume drunk"
 * field, so sips are inferred from the *drop* in fill-level percent between
 * readings, scaled against [BOTTLE_CAPACITY_ML].
 *
 * The AES key is the one literal thing here that can't be derived by
 * watching traffic — it came directly out of the app's decompiled code
 * (`DashboardFragment.key`), and the whole handshake above was confirmed
 * against a real captured challenge/response pair before trusting it.
 */
class ThermosBleClient(private val context: Context) {

    companion object {
        private const val TAG = "ThermosBleClient"
        private const val DEVICE_NAME_PREFIX = "Thermos"

        /**
         * Adjust this to your bottle's real capacity if the inferred sip sizes
         * look off — the capture didn't reveal an explicit capacity field, only
         * percentages, so this scales percent-drop into a volume. ~32oz is the
         * common size for Thermos's connected smart-lid bottle.
         */
        const val BOTTLE_CAPACITY_ML = 950.0

        private fun uuid64ac(shortHex: String): UUID =
            UUID.fromString("$shortHex-4a4b-4b58-9f37-94d3c52ffdf7")

        private fun uuid40fc(shortHex: String): UUID =
            UUID.fromString("$shortHex-8a8d-4a32-a455-c1148e24a9f1")

        // Auth handshake characteristics — service 64ac...
        private val CHAR_APP_CHALLENGE = uuid64ac("64ac0002")    // WRITE: our random nonce
        private val CHAR_DEVICE_CHALLENGE = uuid64ac("64ac0003") // READ: bottle's encrypted challenge
        private val CHAR_DEVICE_RESPONSE = uuid64ac("64ac0004")  // WRITE: our encrypted response

        // Live data characteristics — service 40fc...
        private val CHAR_LEVEL = uuid40fc("40fc0001")    // NOTIFY, 1 byte, live fill %
        private val CHAR_BATTERY = uuid40fc("40fc0002")  // NOTIFY, 1 byte, live battery %
        private val CHAR_COMMAND = uuid40fc("40fc0003")  // WRITE, command channel
        private val CHAR_HISTORY = uuid40fc("40fc0004")  // INDICATE, 8-byte records
        private val CHAR_UNKNOWN5 = uuid40fc("40fc0005") // NOTIFY, purpose unknown

        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Pulled from com.thermos.smartlid's decompiled DashboardFragment.key —
        // a hardcoded AES-128 key, the same across every bottle/install of that
        // app version. Verified by decrypting a real captured device-challenge
        // and confirming the re-encrypted response matches what the real app
        // actually sent on the wire.
        private val AES_KEY = byteArrayOf(
            -6, 111, -124, -125, 24, -31, 76, -44,
            -83, 66, -12, 59, 80, -51, 52, -62,
        )

        // Far-past "since" epoch to use on the very first request of a fresh
        // connection — a stand-in for "the beginning of time" rather than a
        // real filter. Later requests (see [lastHistorySinceBytes]) use the
        // timestamp of the most recently received record instead, matching
        // how the real app re-queries incrementally.
        private val HISTORY_SINCE_BYTES = hexBytes("50-63-7B-67")

        private fun hexBytes(s: String): ByteArray =
            s.split("-").map { it.toInt(16).toByte() }.toByteArray()

        private fun epochLeBytes(epoch: Long): ByteArray = byteArrayOf(
            (epoch and 0xFF).toByte(),
            ((epoch shr 8) and 0xFF).toByte(),
            ((epoch shr 16) and 0xFF).toByte(),
            ((epoch shr 24) and 0xFF).toByte(),
        )

        private fun nowEpochLeBytes(): ByteArray = epochLeBytes(Instant.now().epochSecond)

        /**
         * The real app's `Util.getTimeZone()` / midnight-offset byte: local
         * UTC offset in hours, folded into 0-23 (`24 - offset` when positive,
         * `abs(offset)` when zero or negative). For America/New_York in EDT
         * (UTC-4) this happens to come out to 4 — which is why the earlier
         * hardcoded `0x04` worked, but it'll be wrong in EST or elsewhere.
         */
        private fun midnightOffsetByte(): Byte {
            val offsetHours = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 3600
            val tz = if (offsetHours > 0) 24 - offsetHours else kotlin.math.abs(offsetHours)
            return tz.toByte()
        }

        private fun aesEcb(key: ByteArray, input: ByteArray, encrypt: Boolean): ByteArray {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
            )
            return cipher.doFinal(input)
        }

        /**
         * Turns a series of raw fill-level readings into inferred sips: any time
         * the level drops between two time-ordered readings, that drop (as a
         * fraction of [BOTTLE_CAPACITY_ML]) is one sip. Refills (level going up)
         * are ignored rather than counted as negative drinks.
         */
        fun levelsToSips(readings: List<ThermosLevelReading>): List<ThermosSip> {
            val sorted = readings.sortedBy { it.dateTime }
            val sips = mutableListOf<ThermosSip>()
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val curr = sorted[i]
                val drop = prev.fillLevelPercent - curr.fillLevelPercent
                if (drop > 0) {
                    val volumeMl = (BOTTLE_CAPACITY_ML * drop / 100.0).toInt()
                    if (volumeMl > 0) {
                        sips.add(ThermosSip(curr.dateTime, volumeMl))
                    }
                }
            }
            return sips
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private val readings = mutableListOf<ThermosLevelReading>()

    // GATT only allows one outstanding operation at a time, and the auth
    // handshake's response write depends on data read back from the bottle,
    // so this is driven as an explicit callback chain rather than a fixed
    // queue: each step stashes what should happen next in one of these, and
    // the BluetoothGattCallback methods below look it up and invoke it.
    private var onWriteDone: (() -> Unit)? = null
    private var onReadDone: ((ByteArray) -> Unit)? = null
    private var appNonce: ByteArray = ByteArray(0)

    // Drives the incremental "pull the next batch" protocol: updated to the
    // most recently received history record's own timestamp, and re-sent to
    // CHAR_COMMAND each time the bottle signals "there's more" via a notify
    // on CHAR_LEVEL. Reset to the far-past default on every fresh connect.
    private var lastHistorySinceBytes: ByteArray = HISTORY_SINCE_BYTES
    private var commandChar: BluetoothGattCharacteristic? = null

    @SuppressLint("MissingPermission")
    fun connectAndListen(
        onStatus: (String) -> Unit,
        onRecords: (List<ThermosSip>) -> Unit,
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

        onStatus("Scanning for Thermos bottle…")

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
        onRecords: (List<ThermosSip>) -> Unit,
    ) {
        lastHistorySinceBytes = HISTORY_SINCE_BYTES
        commandChar = null
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    onStatus("Connected. Discovering services…")
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    onStatus("Disconnected (status=$status).")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                Log.d(TAG, "onServicesDiscovered status=$status")
                val level = findChar(g, CHAR_LEVEL)
                val battery = findChar(g, CHAR_BATTERY)
                val command = findChar(g, CHAR_COMMAND)
                val history = findChar(g, CHAR_HISTORY)
                val unknown5 = findChar(g, CHAR_UNKNOWN5)
                val appChallenge = findChar(g, CHAR_APP_CHALLENGE)
                val deviceChallenge = findChar(g, CHAR_DEVICE_CHALLENGE)
                val deviceResponse = findChar(g, CHAR_DEVICE_RESPONSE)

                if (command == null || history == null || appChallenge == null ||
                    deviceChallenge == null || deviceResponse == null
                ) {
                    onStatus("Couldn't find the expected Thermos characteristics.")
                    return
                }

                onStatus("Enabling notifications…")

                // Enable CCCDs, then run the AES auth handshake, then send the
                // history-request commands — each step only starts once the
                // previous one's GATT callback has actually come back.
                maybeEnableNotify(g, level) {
                    maybeEnableNotify(g, battery) {
                        maybeEnableNotify(g, unknown5) {
                            enableIndicate(g, history) {
                                onStatus("Authenticating…")
                                runAuthHandshake(g, appChallenge, deviceChallenge, deviceResponse, onStatus) {
                                    onStatus("Requesting drink history…")
                                    sendCommandSequence(g, command, onStatus)
                                }
                            }
                        }
                    }
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                Log.d(TAG, "Descriptor write on ${descriptor.characteristic.uuid} status=$status")
                val cont = onWriteDone
                onWriteDone = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    cont?.invoke()
                } else {
                    onStatus("Enabling notifications failed (status=$status).")
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                Log.d(TAG, "Characteristic write to ${characteristic.uuid} status=$status")
                val cont = onWriteDone
                onWriteDone = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    cont?.invoke()
                } else {
                    onStatus("Write to ${characteristic.uuid} failed (status=$status).")
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                handleReadResult(characteristic.uuid, characteristic.value, status, onStatus)
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                handleReadResult(characteristic.uuid, value, status, onStatus)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                handleIncoming(characteristic.uuid, characteristic.value, onRecords)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleIncoming(characteristic.uuid, value, onRecords)
            }
        })
    }

    private fun handleReadResult(uuid: UUID, value: ByteArray?, status: Int, onStatus: (String) -> Unit) {
        Log.d(TAG, "Characteristic read from $uuid status=$status value=" +
            (value?.joinToString("-") { String.format("%02X", it) } ?: "null"))
        val cont = onReadDone
        onReadDone = null
        if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
            cont?.invoke(value)
        } else {
            onStatus("Read from $uuid failed (status=$status).")
        }
    }

    private fun findChar(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        g.services?.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    /** level/battery/unknown5 are nice-to-have, not required — skip cleanly if absent. */
    @SuppressLint("MissingPermission")
    private fun maybeEnableNotify(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic?, onDone: () -> Unit) {
        if (characteristic == null) {
            onDone()
            return
        }
        enableNotify(g, characteristic, onDone)
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, onDone: () -> Unit) {
        Log.d(TAG, "Enabling notify on ${characteristic.uuid}")
        g.setCharacteristicNotification(characteristic, true)
        writeCccd(g, characteristic, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, onDone)
    }

    @SuppressLint("MissingPermission")
    private fun enableIndicate(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, onDone: () -> Unit) {
        Log.d(TAG, "Enabling indicate on ${characteristic.uuid}")
        g.setCharacteristicNotification(characteristic, true)
        writeCccd(g, characteristic, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE, onDone)
    }

    @SuppressLint("MissingPermission")
    private fun writeCccd(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, onDone: () -> Unit) {
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.w(TAG, "No CCCD found on ${characteristic.uuid} — skipping, notifications won't arrive for it.")
            onDone()
            return
        }
        onWriteDone = onDone
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") descriptor.value = value
            @Suppress("DEPRECATION") g.writeDescriptor(descriptor)
        }
        if (!started) {
            onWriteDone = null
            Log.w(TAG, "writeDescriptor call on ${characteristic.uuid} failed to start.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeChar(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, onDone: () -> Unit) {
        Log.d(TAG, "Writing to ${characteristic.uuid}: " + value.joinToString("-") { String.format("%02X", it) })
        onWriteDone = onDone
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") characteristic.value = value
            @Suppress("DEPRECATION") characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION") g.writeCharacteristic(characteristic)
        }
        if (!started) {
            onWriteDone = null
            Log.w(TAG, "writeCharacteristic call on ${characteristic.uuid} failed to start.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun readChar(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, onDone: (ByteArray) -> Unit) {
        onReadDone = onDone
        if (!g.readCharacteristic(characteristic)) {
            onReadDone = null
            Log.w(TAG, "readCharacteristic call on ${characteristic.uuid} failed to start.")
        }
    }

    /**
     * The AES-128/ECB challenge-response handshake — see the class doc for
     * what each step means. Calls [onAuthenticated] once the bottle has
     * accepted our response and the command channel should be unlocked.
     */
    private fun runAuthHandshake(
        g: BluetoothGatt,
        appChallengeChar: BluetoothGattCharacteristic,
        deviceChallengeChar: BluetoothGattCharacteristic,
        deviceResponseChar: BluetoothGattCharacteristic,
        onStatus: (String) -> Unit,
        onAuthenticated: () -> Unit,
    ) {
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        appNonce = nonce
        val appChallenge = nonce + ByteArray(8) // zero-padded to 16 bytes

        writeChar(g, appChallengeChar, appChallenge) {
            readChar(g, deviceChallengeChar) { deviceChallengeEncrypted ->
                val decrypted = try {
                    aesEcb(AES_KEY, deviceChallengeEncrypted, encrypt = false)
                } catch (e: Exception) {
                    Log.e(TAG, "AES decrypt failed", e)
                    onStatus("Authentication failed (decrypt error).")
                    return@readChar
                }
                if (decrypted.size < 16 || !decrypted.copyOfRange(0, 8).contentEquals(appNonce)) {
                    onStatus("Authentication failed — bottle's challenge didn't match our nonce.")
                    return@readChar
                }
                val response = ByteArray(8) + decrypted.copyOfRange(8, 16)
                val encryptedResponse = try {
                    aesEcb(AES_KEY, response, encrypt = true)
                } catch (e: Exception) {
                    Log.e(TAG, "AES encrypt failed", e)
                    onStatus("Authentication failed (encrypt error).")
                    return@readChar
                }
                writeChar(g, deviceResponseChar, encryptedResponse) {
                    onAuthenticated()
                }
            }
        }
    }

    private fun sendCommandSequence(g: BluetoothGatt, command: BluetoothGattCharacteristic, onStatus: (String) -> Unit) {
        commandChar = command
        writeChar(g, command, byteArrayOf(0x00, 0x04) + lastHistorySinceBytes) {
            writeChar(g, command, byteArrayOf(0x01, 0x04) + nowEpochLeBytes()) {
                writeChar(g, command, byteArrayOf(0x03, 0x01, midnightOffsetByte())) {
                    // This first 00/01/03 round alone doesn't make the bottle
                    // talk (confirmed from a real capture) — it needs the
                    // "since" write repeated once more to kick off the first
                    // batch of history indications.
                    writeChar(g, command, byteArrayOf(0x00, 0x04) + lastHistorySinceBytes) {
                        onStatus("Listening for drink history…")
                    }
                }
            }
        }
    }

    /**
     * Pulls the next batch of history: re-sends the "since" command using
     * the timestamp of the most recently received record. Called whenever
     * the bottle notifies on CHAR_LEVEL, which is how the real app's own
     * sync loop paces through a long history rather than getting it all in
     * one indication burst.
     */
    @SuppressLint("MissingPermission")
    private fun requestNextHistoryBatch() {
        val g = gatt ?: return
        val command = commandChar ?: return
        if (onWriteDone != null) return // a write is already in flight; don't clobber it
        writeChar(g, command, byteArrayOf(0x00, 0x04) + lastHistorySinceBytes) {}
    }

    private fun handleIncoming(uuid: UUID, data: ByteArray?, onRecords: (List<ThermosSip>) -> Unit) {
        if (data == null) return
        Log.d(TAG, "Notify/indicate from $uuid: " + data.joinToString("-") { String.format("%02X", it) })

        when (uuid) {
            CHAR_HISTORY -> parseHistoryRecord(data)?.let { reading ->
                lastHistorySinceBytes = data.copyOfRange(0, 4)
                readings.add(reading)
                emitSips(onRecords)
            }
            CHAR_LEVEL -> {
                val level = data.getOrNull(0)?.toInt()?.and(0xFF) ?: return
                readings.add(ThermosLevelReading(LocalDateTime.now(), level.coerceIn(0, 100)))
                emitSips(onRecords)
                // The bottle uses this live-level notify as its "there's more
                // history" signal — ask for the next batch.
                requestNextHistoryBatch()
            }
            else -> {
                // 40fc0002 (temperature) and 40fc0005 (battery charging) —
                // logged above for future analysis, not used for sip
                // detection yet.
            }
        }
    }

    private fun emitSips(onRecords: (List<ThermosSip>) -> Unit) {
        val sips = levelsToSips(readings)
        if (sips.isNotEmpty()) onRecords(sips)
    }

    /**
     * 8-byte history record: bytes 0-3 are a little-endian Unix timestamp
     * (verified against real capture times), byte 6 is the fill-level percent
     * that's used here; bytes 4, 5 and 7 (group id, sub-index, battery-ish)
     * aren't needed for sip detection.
     */
    private fun parseHistoryRecord(data: ByteArray): ThermosLevelReading? {
        if (data.size < 7) return null
        val epoch = (data[0].toLong() and 0xFF) or
            ((data[1].toLong() and 0xFF) shl 8) or
            ((data[2].toLong() and 0xFF) shl 16) or
            ((data[3].toLong() and 0xFF) shl 24)
        val level = data[6].toInt() and 0xFF
        if (level > 100) return null
        return try {
            val dt = LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneId.systemDefault())
            ThermosLevelReading(dt, level)
        } catch (e: Exception) {
            Log.w(TAG, "Skipping history record with implausible timestamp", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        commandChar = null
        readings.clear()
        onWriteDone = null
        onReadDone = null
    }
}
