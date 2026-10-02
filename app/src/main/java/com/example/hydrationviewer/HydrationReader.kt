package com.example.hydrationviewer

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class HydrationEntry(
    val volume: Volume,
    val time: Instant,
    val sourceApp: String,
)

data class DailySourceTotals(
    val date: LocalDate,
    val litersBySource: Map<String, Double>,
) {
    val totalLiters: Double get() = litersBySource.values.sum()
}

class HydrationReader(context: Context) {

    private val client: HealthConnectClient = HealthConnectClient.getOrCreate(context)
    private val zone = ZoneId.systemDefault()
    private val ownPackageName = context.packageName

    companion object {
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(HydrationRecord::class),
            HealthPermission.getWritePermission(HydrationRecord::class),
        )
    }

    suspend fun hasPermission(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(REQUIRED_PERMISSIONS)

    /**
     * Health Connect always stamps dataOrigin.packageName as the *writing* app,
     * so both WaterH's and Thermos's entries — which this app writes in on their
     * behalf — carry our own package name, indistinguishable from each other by
     * dataOrigin alone. We tag them instead via the clientRecordId prefix each
     * writeXRecords() function uses ("waterh:…" / "thermos:…") and fall back to
     * dataOrigin for anything genuinely written by another app (Hidrate Spark).
     */
    private fun sourceKey(clientRecordId: String?, dataOriginPackage: String): String = when {
        dataOriginPackage == ownPackageName && clientRecordId?.startsWith("waterh:") == true ->
            "$ownPackageName#waterh"
        dataOriginPackage == ownPackageName && clientRecordId?.startsWith("thermos:") == true ->
            "$ownPackageName#thermos"
        dataOriginPackage == ownPackageName && clientRecordId?.startsWith("manual:") == true ->
            "$ownPackageName#manual"
        else -> dataOriginPackage
    }

    /** Every individual drink logged in the last [days] days, newest first. */
    suspend fun readRecentEntries(days: Long = 7): List<HydrationEntry> {
        val end = Instant.now()
        val start = end.minus(days, ChronoUnit.DAYS)

        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = HydrationRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        ).records

        return records
            .map {
                HydrationEntry(
                    volume = it.volume,
                    time = it.startTime,
                    sourceApp = sourceKey(it.metadata.clientRecordId, it.metadata.dataOrigin.packageName),
                )
            }
            .sortedByDescending { it.time }
    }

    /** Total volume logged today (from local midnight to now). */
    suspend fun todayTotal(): Volume {
        val startOfDay = Instant.now()
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()

        val response = client.aggregate(
            AggregateRequest(
                metrics = setOf(HydrationRecord.VOLUME_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startOfDay, Instant.now())
            )
        )
        return response[HydrationRecord.VOLUME_TOTAL] ?: Volume.milliliters(0.0)
    }

    /**
     * Volume per day for the last [days] days (oldest first), broken down by which
     * app wrote each entry — Hidrate Spark's own writes, and WaterH's entries (which
     * carry this app's own package name, since we write those in ourselves from its
     * Bluetooth data). Computed from raw records rather than the aggregate API, since
     * aggregation doesn't group by source.
     */
    suspend fun dailySourceTotalsForLastDays(days: Int = 7): List<DailySourceTotals> {
        val today = LocalDate.now(zone)
        val rangeStart = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()
        val rangeEnd = today.plusDays(1).atStartOfDay(zone).toInstant()

        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = HydrationRecord::class,
                timeRangeFilter = TimeRangeFilter.between(rangeStart, rangeEnd)
            )
        ).records

        val byDate = records.groupBy { it.startTime.atZone(zone).toLocalDate() }

        val results = mutableListOf<DailySourceTotals>()
        for (i in (days - 1) downTo 0) {
            val date = today.minusDays(i.toLong())
            val dayRecords = byDate[date].orEmpty()
            val bySource = dayRecords
                .groupBy { sourceKey(it.metadata.clientRecordId, it.metadata.dataOrigin.packageName) }
                .mapValues { (_, recs) -> recs.sumOf { it.volume.inLiters } }
            results.add(DailySourceTotals(date, bySource))
        }
        return results
    }

    /**
     * Writes WaterH's Bluetooth-sourced drink records into Health Connect. Each write
     * uses a stable clientRecordId derived from the drink's own timestamp, so calling
     * this repeatedly with overlapping data (e.g. the bottle re-sending its history)
     * updates existing entries instead of creating duplicates.
     */
    suspend fun writeWaterHRecords(records: List<WaterHDrinkRecord>) {
        val toInsert = records.map { drink ->
            val start = drink.dateTime.atZone(zone).toInstant()
            val end = start.plusSeconds(1) // HydrationRecord requires startTime < endTime strictly
            HydrationRecord(
                startTime = start,
                startZoneOffset = zone.rules.getOffset(start),
                endTime = end,
                endZoneOffset = zone.rules.getOffset(end),
                volume = Volume.milliliters(drink.volumeMl.toDouble()),
                metadata = Metadata(
                    clientRecordId = "waterh:${drink.dateTime}",
                ),
            )
        }
        if (toInsert.isNotEmpty()) {
            client.insertRecords(toInsert)
        }
    }

    /**
     * Writes Thermos's Bluetooth-inferred sips into Health Connect, the same
     * idempotent-by-clientRecordId way writeWaterHRecords does.
     */
    suspend fun writeThermosRecords(records: List<ThermosSip>) {
        val toInsert = records.map { sip ->
            val start = sip.dateTime.atZone(zone).toInstant()
            val end = start.plusSeconds(1)
            HydrationRecord(
                startTime = start,
                startZoneOffset = zone.rules.getOffset(start),
                endTime = end,
                endZoneOffset = zone.rules.getOffset(end),
                volume = Volume.milliliters(sip.volumeMl.toDouble()),
                metadata = Metadata(
                    clientRecordId = "thermos:${sip.dateTime}",
                ),
            )
        }
        if (toInsert.isNotEmpty()) {
            client.insertRecords(toInsert)
        }
    }

    /**
     * Writes a single manually-entered drink (typed in as ounces in the UI,
     * converted to mL by the caller) logged at the current time. Each entry
     * gets its own clientRecordId (the write instant, which is unique down
     * to the nanosecond) rather than one derived from the drink's own time,
     * since there's no natural dedupe key for a manual entry the way there
     * is for a bottle's own timestamped history.
     */
    suspend fun writeManualRecord(volumeMl: Double) {
        val now = Instant.now()
        val end = now.plusSeconds(1)
        val record = HydrationRecord(
            startTime = now,
            startZoneOffset = zone.rules.getOffset(now),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            volume = Volume.milliliters(volumeMl),
            metadata = Metadata(
                clientRecordId = "manual:$now",
            ),
        )
        client.insertRecords(listOf(record))
    }
}
