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

    fun estimate(chargeFullMah: Double?, chargeFullDesignMah: Double?): BatteryHealthEstimation? {
        if (chargeFullMah == null || chargeFullDesignMah == null) return null
        if (!chargeFullMah.isFinite() || !chargeFullDesignMah.isFinite()) return null
        if (chargeFullMah <= 0.0 || chargeFullDesignMah <= 0.0) return null

        val ratio = (chargeFullMah / chargeFullDesignMah) * 100.0
        if (ratio < 5.0 || ratio > 130.0) return null

        return BatteryHealthEstimation(
            estimatedPercent = ratio.coerceAtMost(100.0),
            chargeFullMah = chargeFullMah,
            chargeFullDesignMah = chargeFullDesignMah
        )
    }
}

object DrainRateCalculator {

    /**
     * Estimates live %/hour charge or drain rate from battery current (mA) and design/full capacity (mAh).
     * Positive = charging %/h, Negative = discharging %/h.
     */
    fun estimatePctPerHourFromCurrent(
        currentMa: Double?,
        isCharging: Boolean,
        capacityMah: Double?
    ): Double? {
        if (currentMa == null || capacityMah == null || capacityMah <= 0.0) return null
        if (!currentMa.isFinite() || !capacityMah.isFinite()) return null
        val magMa = abs(currentMa)
        if (magMa < 1.0) return 0.0
        val rate = (magMa / capacityMah) * 100.0
        return if (isCharging) rate else -rate
    }

    fun estimateRemainingTimeLabel(
        percentage: Int?,
        isCharging: Boolean,
        ratePctPerHour: Double?
    ): String? {
        if (percentage == null || ratePctPerHour == null) return null
        val absRate = abs(ratePctPerHour)
        if (absRate < 0.5) return null
        val remainingPct = if (isCharging) (100 - percentage).coerceAtLeast(0) else percentage.coerceAtLeast(0)
        if (remainingPct == 0) return if (isCharging) "Full" else "0m"
        val hours = remainingPct / absRate
        val totalMinutes = (hours * 60.0).toInt().coerceIn(1, 2400)
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        val prefix = if (isCharging) "Est. full in " else "Est. empty in "
        return if (h > 0) "$prefix${h}h ${m}m" else "$prefix${m}m"
    }
}

object ThermalParser {

    data class ParsedTemperature(
        val celsius: Double,
        val detectedUnit: String
    )

    fun parseTemperatureCelsius(raw: Long, isBatteryPowerSupplyTemp: Boolean = false): ParsedTemperature? {
        if (raw == -273000L || raw == -273L || raw == -40000L || raw == -400L || raw >= 200_000L) {
            return null
        }
        val absRaw = abs(raw)
        if (isBatteryPowerSupplyTemp) {
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

    /**
     * Parses Linux cpufreq/stats/time_in_state (`<freq_khz> <centiseconds>`).
     */
    fun parseTimeInState(raw: String?): List<CpuFrequencyResidency> {
        if (raw.isNullOrBlank()) return emptyList()
        val rawPairs = raw.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val freqKhz = parts[0].toLongOrNull()
                val ticks10ms = parts[1].toLongOrNull()
                if (freqKhz != null && ticks10ms != null && freqKhz > 0L && ticks10ms >= 0L) {
                    val mhz = UnitConverter.frequencyToMhz(freqKhz)?.value ?: return@mapNotNull null
                    mhz to (ticks10ms * 10L)
                } else null
            } else null
        }.toList()

        val totalMs = rawPairs.sumOf { it.second }
        if (totalMs <= 0L) return emptyList()

        return rawPairs.map { (mhz, ms) ->
            CpuFrequencyResidency(
                freqMhz = mhz,
                timeMs = ms,
                percent = (ms.toDouble() / totalMs.toDouble()) * 100.0
            )
        }
    }
}

object ZramParser {
    data class ZramStats(
        val origDataBytes: Long,
        val comprDataBytes: Long,
        val memUsedTotalBytes: Long,
        val ratio: Double?
    )

    /**
     * Parses `/sys/block/zram0/mm_stat` (orig_data_size compr_data_size mem_used_total ...).
     */
    fun parseMmStat(raw: String?): ZramStats? {
        if (raw.isNullOrBlank()) return null
        val nums = raw.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (nums.size < 3) return null
        val orig = nums[0]
        val compr = nums[1]
        val memUsed = nums[2]
        val ratio = if (orig > 0L && compr > 0L) orig.toDouble() / compr.toDouble() else null
        return ZramStats(orig, compr, memUsed, ratio)
    }
}

object PartitionsParser {
    fun parseProcPartitions(raw: String?): List<PartitionInfo> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().drop(2).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 4) {
                val major = parts[0].toIntOrNull()
                val minor = parts[1].toIntOrNull()
                val blocks = parts[2].toLongOrNull()
                val name = parts[3]
                if (major != null && minor != null && blocks != null) {
                    PartitionInfo(major, minor, blocks, name)
                } else null
            } else null
        }.toList()
    }
}

object EmmcHealthParser {
    /**
     * Parses JEDEC eMMC 5.0+ `life_time` node (two hex bytes: `0x01 0x02`).
     */
    fun decodeLifeTimeHex(hexToken: String?): String? {
        if (hexToken.isNullOrBlank()) return null
        val code = hexToken.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: return null
        return when (code) {
            0x00 -> "Not defined"
            in 0x01..0x0A -> {
                val low = (code - 1) * 10
                val high = code * 10
                "$low% - $high% wear used"
            }
            0x0B -> ">100% (Exceeded max estimated life)"
            else -> "Code 0x${code.toString(16)}"
        }
    }

    fun decodePreEol(raw: String?): Pair<String?, HealthSeverity> {
        if (raw.isNullOrBlank()) return null to HealthSeverity.UNAVAILABLE
        val code = raw.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)
            ?: return raw.trim() to HealthSeverity.UNAVAILABLE
        return when (code) {
            0x01 -> "Normal (< 80% reserved blocks used)" to HealthSeverity.NORMAL
            0x02 -> "Warning (~80% reserved blocks consumed)" to HealthSeverity.WARNING
            0x03 -> "Urgent (>90% reserved blocks consumed)" to HealthSeverity.CRITICAL
            else -> "Not defined (0x${code.toString(16)})" to HealthSeverity.UNAVAILABLE
        }
    }
}
