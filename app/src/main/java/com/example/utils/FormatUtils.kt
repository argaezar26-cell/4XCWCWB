package com.example.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object FormatUtils {

    fun formatDecimal(value: Double?, decimals: Int = 1, suffix: String = ""): String {
        if (value == null || !value.isFinite()) return "N/A"
        val formatted = String.format(Locale.US, "%.${decimals}f", value)
        return if (suffix.isNotEmpty()) "$formatted $suffix" else formatted
    }

    fun formatSignedMa(valueMa: Double?): String {
        if (valueMa == null || !valueMa.isFinite()) return "N/A"
        val sign = if (valueMa > 0) "+" else ""
        return "$sign${String.format(Locale.US, "%.0f", valueMa)} mA"
    }

    fun formatMv(valueMv: Double?): String {
        if (valueMv == null || !valueMv.isFinite()) return "N/A"
        return "${String.format(Locale.US, "%.0f", valueMv)} mV"
    }

    fun formatWatts(watts: Double?): String {
        if (watts == null || !watts.isFinite()) return "N/A"
        return "${String.format(Locale.US, "%.2f", abs(watts))} W"
    }

    fun formatTemp(tempC: Double?): String {
        if (tempC == null || !tempC.isFinite()) return "N/A"
        return "${String.format(Locale.US, "%.1f", tempC)} °C"
    }

    fun formatFreqMhz(freqMhz: Double?): String {
        if (freqMhz == null || !freqMhz.isFinite()) return "N/A"
        return if (freqMhz >= 1000.0) {
            String.format(Locale.US, "%.2f GHz", freqMhz / 1000.0)
        } else {
            String.format(Locale.US, "%.0f MHz", freqMhz)
        }
    }

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0L) return "N/A"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun formatTimestamp(ms: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        return sdf.format(Date(ms))
    }

    fun formatDateTime(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(ms))
    }
}
