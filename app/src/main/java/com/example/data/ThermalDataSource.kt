package com.example.data

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.example.domain.DataSourceOrigin
import com.example.domain.HealthSeverity
import com.example.domain.ThermalParser
import com.example.domain.ThermalTelemetry
import com.example.domain.ThermalTripPoint
import com.example.domain.ThermalZoneCategory
import com.example.domain.ThermalZoneReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThermalDataSource(
    context: Context,
    private val sysfsReader: SysfsReader
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile
    private var maxRecordedTempC: Double? = null

    fun resetPeakRecord() {
        maxRecordedTempC = null
    }

    suspend fun readTelemetry(batteryTempC: Double?): ThermalTelemetry = withContext(Dispatchers.IO) {
        val baseDir = "/sys/class/thermal"
        val (entries, dirUsedRoot) = sysfsReader.listDirectory(baseDir)
        val zoneNames = entries.filter { it.startsWith("thermal_zone") }
            .sortedBy { it.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }

        val thermalFiles = listOf(
            "type", "temp", "mode", "policy",
            "trip_point_0_temp", "trip_point_0_type",
            "trip_point_1_temp", "trip_point_1_type",
            "trip_point_2_temp", "trip_point_2_type",
            "trip_point_3_temp", "trip_point_3_type"
        )
        val batch = sysfsReader.preloadBatchForRoot(
            zoneNames.flatMap { zn -> thermalFiles.map { f -> "$baseDir/$zn/$f" } }
        )

        val zones = zoneNames.map { zoneName ->
            val zonePath = "$baseDir/$zoneName"
            var usedRoot = dirUsedRoot

            val typeRes = batch["$zonePath/type"]
            if (typeRes?.usedRoot == true) usedRoot = true
            val typeStr = typeRes?.content?.takeIf { it.isNotBlank() } ?: zoneName

            val tempRes = batch["$zonePath/temp"]
            if (tempRes?.usedRoot == true) usedRoot = true
            val rawTemp = tempRes?.content?.trim()?.toLongOrNull()

            val modeRes = batch["$zonePath/mode"]
            if (modeRes?.usedRoot == true) usedRoot = true

            val policyRes = batch["$zonePath/policy"]
            if (policyRes?.usedRoot == true) usedRoot = true

            val tripPoints = (0..3).mapNotNull { idx ->
                val tpTempRes = batch["$zonePath/trip_point_${idx}_temp"]
                val tpTypeRes = batch["$zonePath/trip_point_${idx}_type"]
                if (tpTempRes?.usedRoot == true || tpTypeRes?.usedRoot == true) usedRoot = true
                val tpRaw = tpTempRes?.content?.trim()?.toLongOrNull()
                val tpTempC = tpRaw?.let { ThermalParser.parseTemperatureCelsius(it)?.celsius }
                val tpType = tpTypeRes?.content?.trim()?.takeIf { it.isNotBlank() }
                if (tpTempC != null || tpType != null) {
                    ThermalTripPoint(index = idx, type = tpType, tempCelsius = tpTempC)
                } else null
            }

            val category = ThermalParser.classifyZone(typeStr)
            val parsed = rawTemp?.let { ThermalParser.parseTemperatureCelsius(it) }
            val severity = ThermalParser.evaluateZoneSeverity(category, parsed?.celsius)

            ThermalZoneReading(
                zoneName = zoneName,
                path = zonePath,
                type = typeStr,
                category = category,
                temperatureC = parsed?.celsius,
                rawValue = rawTemp,
                detectedUnit = parsed?.detectedUnit ?: "N/A",
                mode = modeRes?.content,
                policy = policyRes?.content,
                source = sysfsReader.resolveSource(DataSourceOrigin.SYSFS_THERMAL, usedRoot),
                severity = severity,
                tripPoints = tripPoints
            )
        }

        val cpuTempC = zones.filter { it.category == ThermalZoneCategory.CPU }
            .mapNotNull { it.temperatureC }
            .maxOrNull()
        val gpuTempC = zones.filter { it.category == ThermalZoneCategory.GPU }
            .mapNotNull { it.temperatureC }
            .maxOrNull()
        val socTempC = zones.filter { it.category == ThermalZoneCategory.SOC }
            .mapNotNull { it.temperatureC }
            .maxOrNull()
            ?: cpuTempC

        val effectiveBatteryTemp = batteryTempC
            ?: zones.firstOrNull { it.category == ThermalZoneCategory.BATTERY }?.temperatureC

        val validZoneTemps = zones.mapNotNull { it.temperatureC }
        val maxZoneTemp = (validZoneTemps + listOfNotNull(effectiveBatteryTemp)).maxOrNull()
        if (maxZoneTemp != null) {
            val prev = maxRecordedTempC
            if (prev == null || maxZoneTemp > prev) {
                maxRecordedTempC = maxZoneTemp
            }
        }

        val (osStatusCode, osStatusLabel) = readPowerManagerThermalStatus()
        val osHeadroom = readThermalHeadroom()

        val worstZoneSeverity = zones.map { it.severity }.fold(HealthSeverity.UNAVAILABLE) { acc, s ->
            when {
                acc == HealthSeverity.CRITICAL || s == HealthSeverity.CRITICAL -> HealthSeverity.CRITICAL
                acc == HealthSeverity.WARNING || s == HealthSeverity.WARNING -> HealthSeverity.WARNING
                acc == HealthSeverity.NORMAL || s == HealthSeverity.NORMAL -> HealthSeverity.NORMAL
                else -> HealthSeverity.UNAVAILABLE
            }
        }

        val overallSeverity = when {
            worstZoneSeverity == HealthSeverity.CRITICAL || (osStatusCode != null && osStatusCode >= 3) -> HealthSeverity.CRITICAL
            worstZoneSeverity == HealthSeverity.WARNING || (osStatusCode != null && osStatusCode >= 2) -> HealthSeverity.WARNING
            worstZoneSeverity == HealthSeverity.NORMAL || osStatusCode != null -> HealthSeverity.NORMAL
            else -> HealthSeverity.UNAVAILABLE
        }

        ThermalTelemetry(
            zones = zones,
            batteryTempC = effectiveBatteryTemp,
            cpuTempC = cpuTempC,
            gpuTempC = gpuTempC,
            socTempC = socTempC,
            maxZoneTempC = maxZoneTemp,
            maxRecordedTempC = maxRecordedTempC,
            osThermalStatusCode = osStatusCode,
            osThermalStatusLabel = osStatusLabel,
            osThermalHeadroom = osHeadroom,
            overallSeverity = overallSeverity
        )
    }

    private fun readPowerManagerThermalStatus(): Pair<Int?, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || powerManager == null) {
            return null to "API < 29 (PowerManager thermal unavailable)"
        }
        return try {
            val status = powerManager.currentThermalStatus
            val label = when (status) {
                PowerManager.THERMAL_STATUS_NONE -> "NONE (Nominal)"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "UNKNOWN ($status)"
            }
            status to label
        } catch (_: Exception) {
            null to "Unavailable"
        }
    }

    private fun readThermalHeadroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || powerManager == null) return null
        return try {
            val headroom = powerManager.getThermalHeadroom(10)
            if (headroom.isNaN() || headroom < 0f) null else headroom
        } catch (_: Exception) {
            null
        }
    }
}
