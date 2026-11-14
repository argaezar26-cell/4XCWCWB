package com.example.domain

import kotlin.math.abs

object PowerCalculator {

    /**
     * Calculates electrical power in Watts using P = V * I.
     * Voltage in mV, Current in mA -> Watts = (mV / 1000) * (mA / 1000).
     * Caller MUST pass voltage and current from the SAME measurement rail
     * (never mix battery voltage with USB input current).
     */
    fun calculatePowerWatts(voltageMv: Double?, currentMa: Double?, useAbsoluteCurrent: Boolean = true): Double? {
        if (voltageMv == null || currentMa == null) return null
        if (!voltageMv.isFinite() || !currentMa.isFinite() || voltageMv <= 0.0) return null
        val effectiveCurrent = if (useAbsoluteCurrent) abs(currentMa) else currentMa
        return (voltageMv / 1000.0) * (effectiveCurrent / 1000.0)
    }

    fun classifyChargingSpeed(watts: Double?, isCharging: Boolean): String {
        if (!isCharging) return "Not charging"
        if (watts == null) return "Unknown rate"
        return when {
            watts < 2.5 -> "Trickle / Slow (< 2.5W)"
            watts < 7.5 -> "Standard USB (2.5W - 7.5W)"
            watts < 12.0 -> "Standard Fast (7.5W - 12W)"
            watts < 20.0 -> "Rapid Charge (12W - 20W)"
            else -> "High-Power Charge (>= 20W)"
        }
    }
}

object BatteryHealthEstimator {

    /**
     * Estimates capacity retention percentage from kernel charge_full and charge_full_design.
     * Returns null if either value is unavailable or non-positive.
     */
    fun estimate(chargeFullMah: Double?, chargeFullDesignMah: Double?): BatteryHealthEstimation? {
        if (chargeFullMah == null || chargeFullDesignMah == null) return null
        if (!chargeFullMah.isFinite() || !chargeFullDesignMah.isFinite()) return null
        if (chargeFullMah <= 0.0 || chargeFullDesignMah <= 0.0) return null

        val ratio = (chargeFullMah / chargeFullDesignMah) * 100.0
        // Ignore implausible kernel garbage (> 130% or < 5%)
        if (ratio < 5.0 || ratio > 130.0) return null

        return BatteryHealthEstimation(
            estimatedPercent = ratio.coerceAtMost(100.0),
            chargeFullMah = chargeFullMah,
            chargeFullDesignMah = chargeFullDesignMah
        )
    }
}

object ThermalParser {

    data class ParsedTemperature(
        val celsius: Double,
        val detectedUnit: String
    )

    /**
     * Parses Linux thermal sysfs or power_supply temp values into Celsius.
     * Rejects known invalid sentinel values (-273000, -40000, 255000, etc.).
     */
    fun parseTemperatureCelsius(raw: Long, isBatteryPowerSupplyTemp: Boolean = false): ParsedTemperature? {
        // Common disconnected / sentinel readings in Qualcomm kernels
        if (raw == -273000L || raw == -273L || raw == -40000L || raw == -400L || raw >= 200_000L) {
            return null
        }
        val absRaw = abs(raw)
        if (isBatteryPowerSupplyTemp) {
            // Linux power_supply/battery/temp is standard tenths of a degree C (e.g., 345 = 34.5 C)
            return when {
                absRaw >= 1000L -> {
                    val c = raw / 1000.0
                    if (c in -30.0..120.0) ParsedTemperature(c, "m°C") else null
                }
                absRaw in 100L..999L -> {
                    val c = raw / 10.0
                    if (c in -30.0..120.0) ParsedTemperature(c, "0.1°C") else null
                }
                else -> {
                    val c = raw.toDouble()
                    if (c in -30.0..120.0) ParsedTemperature(c, "°C") else null
                }
            }
        }

        // Standard /sys/class/thermal/thermal_zone*/temp is millidegrees Celsius (e.g. 42500 -> 42.5 C)
        return when {
            absRaw >= 1_500L -> {
                val c = raw / 1000.0
                if (c in -30.0..135.0) ParsedTemperature(c, "m°C") else null
            }
            absRaw in 150L..1_499L -> {
                val c = raw / 10.0
                if (c in -30.0..135.0) ParsedTemperature(c, "0.1°C") else null
            }
            else -> {
                val c = raw.toDouble()
                if (c in -30.0..135.0 && raw != 0L) ParsedTemperature(c, "°C") else null
            }
        }
    }

    fun classifyZone(type: String): ThermalZoneCategory {
        val lower = type.lowercase()
        return when {
            lower.contains("batt") || lower == "bms" -> ThermalZoneCategory.BATTERY
            lower.contains("gpu") || lower.contains("kgsl") -> ThermalZoneCategory.GPU
            lower.contains("cpu") || lower.contains("apss") || lower.contains("cluster") ||
                lower.contains("tsens") -> ThermalZoneCategory.CPU
            lower.contains("soc") || lower.contains("msm_therm") || lower.contains("sdm") -> ThermalZoneCategory.SOC
            lower.contains("pm89") || lower.contains("pmi89") || lower.contains("pmic") ||
                lower.contains("xo_therm") || lower.contains("quiet_therm") ||
                lower.contains("pa_therm") || lower.contains("skin") || lower.contains("board") -> ThermalZoneCategory.PMIC_BOARD
            else -> ThermalZoneCategory.OTHER
        }
    }

    fun evaluateZoneSeverity(category: ThermalZoneCategory, tempC: Double?): HealthSeverity {
        if (tempC == null) return HealthSeverity.UNAVAILABLE
        return when (category) {
            ThermalZoneCategory.BATTERY -> when {
                tempC >= 45.0 || tempC <= 0.0 -> HealthSeverity.CRITICAL
                tempC >= 40.0 || tempC < 5.0 -> HealthSeverity.WARNING
                else -> HealthSeverity.NORMAL
            }
            ThermalZoneCategory.CPU, ThermalZoneCategory.GPU, ThermalZoneCategory.SOC -> when {
                tempC >= 85.0 -> HealthSeverity.CRITICAL
                tempC >= 70.0 -> HealthSeverity.WARNING
                else -> HealthSeverity.NORMAL
            }
            ThermalZoneCategory.PMIC_BOARD, ThermalZoneCategory.OTHER -> when {
                tempC >= 80.0 -> HealthSeverity.CRITICAL
                tempC >= 60.0 -> HealthSeverity.WARNING
                else -> HealthSeverity.NORMAL
            }
        }
    }
}

object CpuFreqParser {

    data class ProcStatSample(
        val label: String,
        val totalTime: Long,
        val idleTime: Long
    )

    fun parseFrequencyListMhz(raw: String?): List<Double> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.trim()
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                token.toLongOrNull()?.let { UnitConverter.frequencyToMhz(it)?.value }
            }
            .distinct()
            .sorted()
    }

    fun parseSpaceSeparatedTokens(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
    }

    fun parseProcStat(raw: String?): Map<String, ProcStatSample> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, ProcStatSample>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("cpu")) {
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size >= 5) {
                    val label = parts[0]
                    val numbers = parts.drop(1).mapNotNull { it.toLongOrNull() }
                    if (numbers.size >= 4) {
                        val idle = numbers[3] + (numbers.getOrNull(4) ?: 0L)
                        val total = numbers.sum()
                        result[label] = ProcStatSample(label, total, idle)
                    }
                }
            }
        }
        return result
    }

    fun computeUtilizationPercent(prev: ProcStatSample?, curr: ProcStatSample?): Double? {
        if (prev == null || curr == null) return null
        val totalDelta = curr.totalTime - prev.totalTime
        val idleDelta = curr.idleTime - prev.idleTime
        if (totalDelta <= 0L) return null
        val activeDelta = (totalDelta - idleDelta).coerceAtLeast(0L)
        return ((activeDelta.toDouble() / totalDelta.toDouble()) * 100.0).coerceIn(0.0, 100.0)
    }

    fun parseCpuInfo(raw: String?): Triple<String, String, String> {
        if (raw.isNullOrBlank()) return Triple("Unavailable", "Unavailable", System.getProperty("os.arch") ?: "Unknown")
        var processor = ""
        var hardware = ""
        var architecture = System.getProperty("os.arch") ?: "Unknown"
        raw.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                when {
                    key.equals("model name", ignoreCase = true) || key.equals("Processor", ignoreCase = true) -> {
                        if (processor.isBlank() && value.isNotBlank()) processor = value
                    }
                    key.equals("Hardware", ignoreCase = true) -> {
                        if (hardware.isBlank() && value.isNotBlank()) hardware = value
                    }
                    key.equals("CPU architecture", ignoreCase = true) -> {
                        if (value.isNotBlank()) architecture = "ARMv$value (${System.getProperty("os.arch") ?: "arm"})"
                    }
                }
            }
        }
        val finalModel = when {
            processor.isNotBlank() -> processor
            hardware.isNotBlank() -> hardware
            else -> android.os.Build.HARDWARE.ifBlank { "Unavailable" }
        }
        val finalHardware = hardware.ifBlank { android.os.Build.HARDWARE.ifBlank { "Unavailable" } }
        return Triple(finalModel, finalHardware, architecture)
    }

    fun parseMemInfo(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        val map = LinkedHashMap<String, Long>()
        raw.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val valuePart = line.substring(idx + 1).trim()
                val kb = valuePart.split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
                if (kb != null) {
                    map[key] = kb * 1024L
                }
            }
        }
        return map
    }
}
