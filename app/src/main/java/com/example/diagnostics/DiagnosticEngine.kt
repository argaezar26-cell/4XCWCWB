package com.example.diagnostics

import com.example.domain.DiagnosticCheckResult
import com.example.domain.DiagnosticReport
import com.example.domain.DiagnosticStatus
import com.example.domain.HardwareSnapshot
import com.example.domain.HealthSeverity
import com.example.domain.RootStatus
import com.example.utils.FormatUtils

object DiagnosticEngine {

    fun runDiagnostics(snapshot: HardwareSnapshot): DiagnosticReport {
        val checks = listOf(
            checkBattery(snapshot),
            checkCharging(snapshot),
            checkThermal(snapshot),
            checkCpu(snapshot),
            checkGpu(snapshot),
            checkMemory(snapshot),
            checkStorage(snapshot),
            checkSensors(snapshot),
            checkRoot(snapshot),
            checkKernel(snapshot)
        )

        return DiagnosticReport(
            generatedAtMs = System.currentTimeMillis(),
            deviceLabel = "${snapshot.kernelSystem.deviceModel} (${snapshot.kernelSystem.deviceCodename})",
            checks = checks,
            passCount = checks.count { it.status == DiagnosticStatus.PASS },
            warningCount = checks.count { it.status == DiagnosticStatus.WARNING },
            unavailableCount = checks.count { it.status == DiagnosticStatus.UNAVAILABLE },
            errorCount = checks.count { it.status == DiagnosticStatus.ERROR }
        )
    }

    private fun checkBattery(s: HardwareSnapshot): DiagnosticCheckResult {
        val b = s.battery
        val pct = b.percentage.value
        val volt = b.voltageMv.value
        val temp = b.temperatureC.value

        if (pct == null && volt == null && temp == null) {
            return DiagnosticCheckResult(
                id = "battery",
                category = "Battery Telemetry",
                status = DiagnosticStatus.UNAVAILABLE,
                summary = "No battery telemetry accessible",
                technicalReason = "Neither /sys/class/power_supply/battery nor BatteryManager returned valid cell metrics."
            )
        }
        val status = when (b.severity) {
            HealthSeverity.CRITICAL -> DiagnosticStatus.ERROR
            HealthSeverity.WARNING -> DiagnosticStatus.WARNING
            HealthSeverity.NORMAL -> DiagnosticStatus.PASS
            HealthSeverity.UNAVAILABLE -> DiagnosticStatus.UNAVAILABLE
        }
        val estHealth = b.estimatedHealth?.let {
            " Est. retention ${FormatUtils.formatDecimal(it.estimatedPercent, 1, "%")}."
        } ?: ""
        return DiagnosticCheckResult(
            id = "battery",
            category = "Battery Telemetry",
            status = status,
            summary = "${pct?.let { "$it%" } ?: "N/A"} | ${FormatUtils.formatMv(volt)} | ${FormatUtils.formatTemp(temp)}",
            technicalReason = "Source: ${b.voltageMv.source.label}. Android health: ${b.healthAndroid.value ?: "Unknown"}.$estHealth"
        )
    }

    private fun checkCharging(s: HardwareSnapshot): DiagnosticCheckResult {
        val c = s.charging
        if (!c.isChargerConnected) {
            return DiagnosticCheckResult(
                id = "charging",
                category = "Charging Subsystem",
                status = DiagnosticStatus.PASS,
                summary = "Discharging on battery",
                technicalReason = "No external charger connected; USB/AC rail inactive as expected."
            )
        }
        val hasUsbPower = c.usbPowerWatts.value != null
        val hasBattPower = c.batteryPowerWatts.value != null
        return if (c.isCharging && (hasBattPower || hasUsbPower)) {
            DiagnosticCheckResult(
                id = "charging",
                category = "Charging Subsystem",
                status = DiagnosticStatus.PASS,
                summary = "${c.chargingSource} • ${c.chargingSpeedLabel}",
                technicalReason = "Batt rail: ${FormatUtils.formatWatts(c.batteryPowerWatts.value)}, USB rail: ${FormatUtils.formatWatts(c.usbPowerWatts.value)}."
            )
        } else {
            DiagnosticCheckResult(
                id = "charging",
                category = "Charging Subsystem",
                status = DiagnosticStatus.WARNING,
                summary = "${c.chargingSource} connected, limited telemetry",
                technicalReason = "Charger detected but charging current/power nodes partially unavailable or trickle/paused."
            )
        }
    }

    private fun checkThermal(s: HardwareSnapshot): DiagnosticCheckResult {
        val t = s.thermal
        if (t.zones.isEmpty() && t.batteryTempC == null) {
            return DiagnosticCheckResult(
                id = "thermal",
                category = "Thermal Sensors",
                status = DiagnosticStatus.UNAVAILABLE,
                summary = "Thermal sysfs zones not readable",
                technicalReason = "/sys/class/thermal/thermal_zone* returned no readable zones (SELinux or kernel restriction)."
            )
        }
        val status = when (t.overallSeverity) {
            HealthSeverity.CRITICAL -> DiagnosticStatus.ERROR
            HealthSeverity.WARNING -> DiagnosticStatus.WARNING
            HealthSeverity.NORMAL -> DiagnosticStatus.PASS
            HealthSeverity.UNAVAILABLE -> DiagnosticStatus.UNAVAILABLE
        }
        val activeCount = t.zones.count { it.temperatureC != null }
        return DiagnosticCheckResult(
            id = "thermal",
            category = "Thermal Sensors",
            status = status,
            summary = "$activeCount/${t.zones.size} zones active • Peak ${FormatUtils.formatTemp(t.maxZoneTempC)}",
            technicalReason = "OS Thermal Status: ${t.osThermalStatusLabel}. CPU: ${FormatUtils.formatTemp(t.cpuTempC)}, Battery: ${FormatUtils.formatTemp(t.batteryTempC)}."
        )
    }

    private fun checkCpu(s: HardwareSnapshot): DiagnosticCheckResult {
        val cpu = s.cpu
        val readableFreqCores = cpu.cores.count { it.currentFreqMhz != null }
        val deepSleepNote = if (cpu.totalElapsedMs > 0L) {
            " Deep Sleep: ${FormatUtils.formatDecimal(cpu.deepSleepPercent, 1, "%")}."
        } else ""
        return if (readableFreqCores > 0) {
            DiagnosticCheckResult(
                id = "cpu",
                category = "CPU Scaling & Telemetry",
                status = DiagnosticStatus.PASS,
                summary = "${cpu.onlineCores}/${cpu.totalCores} cores online • ${readableFreqCores} cpufreq nodes",
                technicalReason = "Model: ${cpu.modelName}. Load: ${FormatUtils.formatDecimal(cpu.totalUtilizationPercent, 1, "%")}.$deepSleepNote"
            )
        } else {
            DiagnosticCheckResult(
                id = "cpu",
                category = "CPU Scaling & Telemetry",
                status = DiagnosticStatus.UNAVAILABLE,
                summary = "${cpu.totalCores} cores detected, cpufreq restricted",
                technicalReason = "Kernel cpufreq sysfs nodes unreadable without elevated SELinux/root permission (not a hardware fault).$deepSleepNote"
            )
        }
    }

    private fun checkGpu(s: HardwareSnapshot): DiagnosticCheckResult {
        val gpu = s.gpu
        return if (gpu.isSysfsAvailable) {
            DiagnosticCheckResult(
                id = "gpu",
                category = "GPU Telemetry",
                status = DiagnosticStatus.PASS,
                summary = "${gpu.gpuName} • ${FormatUtils.formatFreqMhz(gpu.currentFreqMhz)}",
                technicalReason = "Read from ${gpu.sysfsNodePath ?: gpu.source.label} (Max: ${FormatUtils.formatFreqMhz(gpu.maxFreqMhz)})."
            )
        } else if (gpu.gpuName != "Unavailable") {
            DiagnosticCheckResult(
                id = "gpu",
                category = "GPU Telemetry",
                status = DiagnosticStatus.UNAVAILABLE,
                summary = "${gpu.gpuName} (sysfs clock unavailable)",
                technicalReason = "OpenGL ES driver identified renderer, but /sys/class/kgsl/kgsl-3d0 or devfreq nodes are absent or restricted."
            )
        } else {
            DiagnosticCheckResult(
                id = "gpu",
                category = "GPU Telemetry",
                status = DiagnosticStatus.UNAVAILABLE,
                summary = "GPU sysfs nodes unavailable",
                technicalReason = "Neither KGSL nor GPU devfreq sysfs interfaces exposed readable nodes."
            )
        }
    }

    private fun checkMemory(s: HardwareSnapshot): DiagnosticCheckResult {
        val m = s.memory
        val status = when {
            m.isLowMemory || m.utilizationPercent >= 92.0 -> DiagnosticStatus.WARNING
            m.totalRamBytes > 0L -> DiagnosticStatus.PASS
            else -> DiagnosticStatus.ERROR
        }
        return DiagnosticCheckResult(
            id = "memory",
            category = "RAM & Swap",
            status = status,
            summary = "${FormatUtils.formatBytes(m.usedRamBytes)} / ${FormatUtils.formatBytes(m.totalRamBytes)} (${FormatUtils.formatDecimal(m.utilizationPercent, 1, "%")})",
            technicalReason = m.interpretation
        )
    }

    private fun checkStorage(s: HardwareSnapshot): DiagnosticCheckResult {
        val st = s.storage
        val emmc = st.emmcHealth
        val status = when {
            st.internalTotalBytes <= 0L || emmc?.preEolSeverity == HealthSeverity.CRITICAL -> DiagnosticStatus.ERROR
            st.usagePercent >= 92.0 || emmc?.preEolSeverity == HealthSeverity.WARNING -> DiagnosticStatus.WARNING
            else -> DiagnosticStatus.PASS
        }
        val emmcNote = emmc?.let {
            val parts = listOfNotNull(
                it.deviceName?.let { n -> "eMMC $n" },
                it.preEolStatus?.let { eol -> "Pre-EOL: $eol" },
                it.lifeTimeTypeA?.let { a -> "SLC/TypeA: $a" },
                it.lifeTimeTypeB?.let { b -> "MLC/TypeB: $b" }
            )
            if (parts.isNotEmpty()) " ${parts.joinToString(" | ")}." else ""
        } ?: ""
        return DiagnosticCheckResult(
            id = "storage",
            category = "Internal Storage & Flash",
            status = status,
            summary = "${FormatUtils.formatBytes(st.internalUsedBytes)} / ${FormatUtils.formatBytes(st.internalTotalBytes)} (${FormatUtils.formatDecimal(st.usagePercent, 1, "%")})",
            technicalReason = "${st.diagnosticNote}$emmcNote"
        )
    }

    private fun checkSensors(s: HardwareSnapshot): DiagnosticCheckResult {
        val available = s.sensors.count { it.isAvailable }
        val missing = s.sensors.count { !it.isAvailable }
        return if (available > 0) {
            DiagnosticCheckResult(
                id = "sensors",
                category = "Hardware Sensors",
                status = DiagnosticStatus.PASS,
                summary = "$available sensors present ($missing optional absent)",
                technicalReason = "Enumerated via Android SensorManager; missing optional sensors (e.g., barometer/ambient temp on Santoni) are normal."
            )
        } else {
            DiagnosticCheckResult(
                id = "sensors",
                category = "Hardware Sensors",
                status = DiagnosticStatus.UNAVAILABLE,
                summary = "No sensors reported",
                technicalReason = "SensorManager returned an empty sensor inventory."
            )
        }
    }

    private fun checkRoot(s: HardwareSnapshot): DiagnosticCheckResult {
        val k = s.kernelSystem
        return when (k.rootStatus) {
            RootStatus.ROOT_AVAILABLE -> DiagnosticCheckResult(
                id = "root",
                category = "Root & Magisk Probe",
                status = DiagnosticStatus.PASS,
                summary = "ROOT AVAILABLE${k.magiskVersion?.let { " • Magisk $it" } ?: ""}",
                technicalReason = "Verified via read-only `su -c id`: ${k.rootProbeDetail}"
            )
            RootStatus.ROOT_DENIED -> DiagnosticCheckResult(
                id = "root",
                category = "Root & Magisk Probe",
                status = DiagnosticStatus.WARNING,
                summary = "ROOT DENIED",
                technicalReason = "${k.rootProbeDetail}. App continues using standard non-root Android & readable sysfs APIs."
            )
            RootStatus.ROOT_NOT_FOUND -> DiagnosticCheckResult(
                id = "root",
                category = "Root & Magisk Probe",
                status = DiagnosticStatus.UNAVAILABLE,
                summary = "ROOT NOT FOUND",
                technicalReason = "${k.rootProbeDetail}. Kernel nodes restricted by SELinux will remain marked UNAVAILABLE."
            )
        }
    }

    private fun checkKernel(s: HardwareSnapshot): DiagnosticCheckResult {
        val k = s.kernelSystem
        return DiagnosticCheckResult(
            id = "kernel",
            category = "Kernel & System Build",
            status = DiagnosticStatus.PASS,
            summary = "Android ${k.androidVersion} (API ${k.sdkInt}) • Kernel ${k.kernelVersion}",
            technicalReason = "Device: ${k.deviceModel} (${k.deviceCodename}), SELinux: ${k.selinuxStatus}, ABIs: ${k.supportedAbis.joinToString()}."
        )
    }

    fun formatAsText(report: DiagnosticReport, snapshot: HardwareSnapshot): String = buildString {
        appendLine("============================================================")
        appendLine("REDMI 4X HARDWARE LAB - DIAGNOSTIC REPORT")
        appendLine("============================================================")
        appendLine("Generated : ${FormatUtils.formatDateTime(report.generatedAtMs)}")
        appendLine("Device    : ${report.deviceLabel}")
        appendLine("Android   : ${snapshot.kernelSystem.androidVersion} (API ${snapshot.kernelSystem.sdkInt})")
        appendLine("Kernel    : ${snapshot.kernelSystem.kernelVersion}")
        appendLine("Root      : ${snapshot.kernelSystem.rootStatus.label}")
        appendLine("Summary   : PASS=${report.passCount}, WARNING=${report.warningCount}, UNAVAILABLE=${report.unavailableCount}, ERROR=${report.errorCount}")
        appendLine("------------------------------------------------------------")
        report.checks.forEach { check ->
            appendLine("[${check.status.name}] ${check.category}")
            appendLine("  Summary : ${check.summary}")
            appendLine("  Reason  : ${check.technicalReason}")
            appendLine()
        }
        appendLine("------------------------------------------------------------")
        appendLine("RAW TELEMETRY SNAPSHOT (v2.0.0)")
        appendLine("  Battery : ${snapshot.battery.percentage.value ?: "N/A"}% | ${FormatUtils.formatMv(snapshot.battery.voltageMv.value)} | ${FormatUtils.formatSignedMa(snapshot.battery.currentNowMa.value)} | ${FormatUtils.formatTemp(snapshot.battery.temperatureC.value)}")
        appendLine("  USB Rail: ${FormatUtils.formatMv(snapshot.charging.usbVoltageMv.value)} | ${FormatUtils.formatSignedMa(snapshot.charging.usbCurrentMa.value)} | ${FormatUtils.formatWatts(snapshot.charging.usbPowerWatts.value)}")
        appendLine("  CPU     : ${snapshot.cpu.modelName} (${snapshot.cpu.onlineCores}/${snapshot.cpu.totalCores} cores) Load=${FormatUtils.formatDecimal(snapshot.cpu.totalUtilizationPercent, 1, "%")} DeepSleep=${FormatUtils.formatDecimal(snapshot.cpu.deepSleepPercent, 1, "%")}")
        appendLine("  GPU     : ${snapshot.gpu.gpuName} | Clock=${FormatUtils.formatFreqMhz(snapshot.gpu.currentFreqMhz)}")
        appendLine("  RAM     : ${FormatUtils.formatBytes(snapshot.memory.usedRamBytes)} / ${FormatUtils.formatBytes(snapshot.memory.totalRamBytes)} | zRAM Ratio=${FormatUtils.formatDecimal(snapshot.memory.zramCompressionRatio, 2, "x")}")
        appendLine("  Storage : ${FormatUtils.formatBytes(snapshot.storage.internalUsedBytes)} / ${FormatUtils.formatBytes(snapshot.storage.internalTotalBytes)} (${snapshot.storage.primaryFsType ?: "N/A"})")
        snapshot.storage.emmcHealth?.let { emmc ->
            appendLine("  eMMC    : ${emmc.deviceName ?: "N/A"} | Pre-EOL=${emmc.preEolStatus ?: "N/A"} | WearA=${emmc.lifeTimeTypeA ?: "N/A"}")
        }
        snapshot.kernelSystem.displayInfo?.let { d ->
            appendLine("  Display : ${d.widthPx}x${d.heightPx} @ ${d.densityDpi}dpi | ${FormatUtils.formatDecimal(d.refreshRateHz.toDouble(), 0, "Hz")}")
        }
        appendLine("============================================================")
    }

    fun formatAsCsv(report: DiagnosticReport, snapshot: HardwareSnapshot): String = buildString {
        appendLine("timestamp_ms,device,check_id,category,status,summary,technical_reason")
        report.checks.forEach { check ->
            appendLine(
                listOf(
                    report.generatedAtMs.toString(),
                    escapeCsv(report.deviceLabel),
                    escapeCsv(check.id),
                    escapeCsv(check.category),
                    escapeCsv(check.status.name),
                    escapeCsv(check.summary),
                    escapeCsv(check.technicalReason)
                ).joinToString(",")
            )
        }
    }

    fun formatAsJson(report: DiagnosticReport, snapshot: HardwareSnapshot): String = buildString {
        fun q(s: String): String = "\"${escapeJson(s)}\""
        fun optStr(s: String?): String = if (s == null) "null" else q(s)
        fun optNum(n: Double?): String = if (n == null || !n.isFinite()) "null" else n.toString()

        appendLine("{")
        appendLine("  \"appVersion\": \"2.0.0\",")
        appendLine("  \"generatedAtMs\": ${report.generatedAtMs},")
        appendLine("  \"generatedAtIso\": ${q(FormatUtils.formatDateTime(report.generatedAtMs))},")
        appendLine("  \"deviceLabel\": ${q(report.deviceLabel)},")
        appendLine("  \"summary\": {")
        appendLine("    \"pass\": ${report.passCount},")
        appendLine("    \"warning\": ${report.warningCount},")
        appendLine("    \"unavailable\": ${report.unavailableCount},")
        appendLine("    \"error\": ${report.errorCount}")
        appendLine("  },")
        appendLine("  \"checks\": [")
        report.checks.forEachIndexed { idx, c ->
            val comma = if (idx < report.checks.lastIndex) "," else ""
            appendLine("    {")
            appendLine("      \"id\": ${q(c.id)},")
            appendLine("      \"category\": ${q(c.category)},")
            appendLine("      \"status\": ${q(c.status.name)},")
            appendLine("      \"summary\": ${q(c.summary)},")
            appendLine("      \"technicalReason\": ${q(c.technicalReason)}")
            appendLine("    }$comma")
        }
        appendLine("  ],")
        appendLine("  \"telemetry\": {")
        appendLine("    \"battery\": {")
        appendLine("      \"percentage\": ${snapshot.battery.percentage.value?.toString() ?: "null"},")
        appendLine("      \"voltageMv\": ${optNum(snapshot.battery.voltageMv.value)},")
        appendLine("      \"currentNowMa\": ${optNum(snapshot.battery.currentNowMa.value)},")
        appendLine("      \"temperatureC\": ${optNum(snapshot.battery.temperatureC.value)},")
        appendLine("      \"chargeType\": ${optStr(snapshot.battery.chargeType.value)},")
        appendLine("      \"internalResistanceMohm\": ${optNum(snapshot.battery.internalResistanceMohm.value)},")
        appendLine("      \"drainOrChargePctPerHour\": ${optNum(snapshot.battery.drainOrChargeRatePctPerHour)}")
        appendLine("    },")
        appendLine("    \"cpu\": {")
        appendLine("      \"modelName\": ${q(snapshot.cpu.modelName)},")
        appendLine("      \"totalCores\": ${snapshot.cpu.totalCores},")
        appendLine("      \"onlineCores\": ${snapshot.cpu.onlineCores},")
        appendLine("      \"utilizationPercent\": ${optNum(snapshot.cpu.totalUtilizationPercent)},")
        appendLine("      \"deepSleepMs\": ${snapshot.cpu.deepSleepMs},")
        appendLine("      \"deepSleepPercent\": ${optNum(snapshot.cpu.deepSleepPercent)}")
        appendLine("    },")
        appendLine("    \"gpu\": {")
        appendLine("      \"name\": ${q(snapshot.gpu.gpuName)},")
        appendLine("      \"currentFreqMhz\": ${optNum(snapshot.gpu.currentFreqMhz)},")
        appendLine("      \"utilizationPercent\": ${optNum(snapshot.gpu.utilizationPercent)}")
        appendLine("    },")
        appendLine("    \"memory\": {")
        appendLine("      \"totalRamBytes\": ${snapshot.memory.totalRamBytes},")
        appendLine("      \"usedRamBytes\": ${snapshot.memory.usedRamBytes},")
        appendLine("      \"zramCompressionRatio\": ${optNum(snapshot.memory.zramCompressionRatio)}")
        appendLine("    },")
        appendLine("    \"storage\": {")
        appendLine("      \"internalTotalBytes\": ${snapshot.storage.internalTotalBytes},")
        appendLine("      \"internalUsedBytes\": ${snapshot.storage.internalUsedBytes},")
        appendLine("      \"primaryFsType\": ${optStr(snapshot.storage.primaryFsType)},")
        appendLine("      \"emmcPreEol\": ${optStr(snapshot.storage.emmcHealth?.preEolStatus)},")
        appendLine("      \"emmcWearTypeA\": ${optStr(snapshot.storage.emmcHealth?.lifeTimeTypeA)}")
        appendLine("    },")
        appendLine("    \"kernelSystem\": {")
        appendLine("      \"androidVersion\": ${q(snapshot.kernelSystem.androidVersion)},")
        appendLine("      \"sdkInt\": ${snapshot.kernelSystem.sdkInt},")
        appendLine("      \"kernelVersion\": ${q(snapshot.kernelSystem.kernelVersion)},")
        appendLine("      \"rootStatus\": ${q(snapshot.kernelSystem.rootStatus.label)},")
        appendLine("      \"selinuxStatus\": ${q(snapshot.kernelSystem.selinuxStatus)}")
        appendLine("    }")
        appendLine("  }")
        append("}")
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch.code < 0x20) {
                    append(String.format("\\u%04x", ch.code))
                } else {
                    append(ch)
                }
            }
        }
    }

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
