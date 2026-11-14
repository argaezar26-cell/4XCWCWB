package com.example.domain

import kotlin.math.abs

data class ConvertedUnit(
    val value: Double,
    val unit: String,
    val detectedSourceUnit: String
)

object UnitConverter {

    /**
     * Converts sysfs or BatteryManager voltage values into millivolts (mV).
     * - |raw| >= 100_000 is treated as microvolts (uV), standard in Linux power_supply.
     * - |raw| in 100..99_999 is treated as millivolts (mV), standard in Android BatteryManager.
     * - |raw| in 1..99 is treated as volts (V).
     */
    fun toMillivolts(raw: Long, assumeMicrovoltsIfAmbiguous: Boolean = true): ConvertedUnit? {
        if (raw <= 0L) return null
        val absRaw = abs(raw)
        return when {
            absRaw >= 100_000L -> ConvertedUnit(raw / 1000.0, "mV", "µV")
            absRaw >= 100L -> ConvertedUnit(raw.toDouble(), "mV", "mV")
            assumeMicrovoltsIfAmbiguous && absRaw < 100L -> ConvertedUnit(raw * 1000.0, "mV", "V")
            else -> ConvertedUnit(raw.toDouble(), "mV", "mV")
        }
    }

    /**
     * Converts sysfs or BatteryManager current values into milliamperes (mA), preserving sign.
     * - |raw| >= 10_000 is treated as microamperes (uA), e.g., 15_000 uA = 15 mA, 1_800_000 uA = 1800 mA.
     * - |raw| < 10_000 is treated as milliamperes (mA) unless forcedMicroAmperes is true.
     * Note: Zero is valid (0.0 mA).
     */
    fun toMilliamperes(raw: Long, forceMicroAmperes: Boolean = false): ConvertedUnit {
        if (raw == 0L) {
            return ConvertedUnit(0.0, "mA", if (forceMicroAmperes) "µA" else "mA")
        }
        val absRaw = abs(raw)
        return if (forceMicroAmperes || absRaw >= 10_000L) {
            ConvertedUnit(raw / 1000.0, "mA", "µA")
        } else {
            ConvertedUnit(raw.toDouble(), "mA", "mA")
        }
    }

    /**
     * Converts Linux power_supply charge_counter / charge_full / charge_full_design into mAh.
     * - raw >= 50_000 is treated as microampere-hours (uAh), e.g., 4_100_000 uAh = 4100 mAh.
     * - raw in 1..49_999 is treated as milliampere-hours (mAh).
     */
    fun toMilliampHours(raw: Long): ConvertedUnit? {
        if (raw <= 0L) return null
        return if (raw >= 50_000L) {
            ConvertedUnit(raw / 1000.0, "mAh", "µAh")
        } else {
            ConvertedUnit(raw.toDouble(), "mAh", "mAh")
        }
    }

    /**
     * Converts cpufreq / devfreq / kgsl frequency into MHz.
     * - raw >= 10_000_000 is treated as Hz (e.g., KGSL/devfreq 400_000_000 Hz = 400 MHz).
     * - raw in 10_000..9_999_999 is treated as kHz (e.g., cpufreq 1_401_600 kHz = 1401.6 MHz).
     * - raw in 1..9_999 is treated as MHz.
     */
    fun frequencyToMhz(raw: Long): ConvertedUnit? {
        if (raw <= 0L) return null
        return when {
            raw >= 10_000_000L -> ConvertedUnit(raw / 1_000_000.0, "MHz", "Hz")
            raw >= 10_000L -> ConvertedUnit(raw / 1000.0, "MHz", "kHz")
            else -> ConvertedUnit(raw.toDouble(), "MHz", "MHz")
        }
    }
}
