package com.example

import com.example.diagnostics.DiagnosticEngine
import com.example.domain.BatteryHealthEstimator
import com.example.domain.BatteryTelemetry
import com.example.domain.ChargingTelemetry
import com.example.domain.CpuFreqParser
import com.example.domain.CpuTelemetry
import com.example.domain.DataSourceOrigin
import com.example.domain.DrainRateCalculator
import com.example.domain.EmmcHealthParser
import com.example.domain.GpuTelemetry
import com.example.domain.HardwareSnapshot
import com.example.domain.HealthSeverity
import com.example.domain.KernelSystemInfo
import com.example.domain.MemoryTelemetry
import com.example.domain.PartitionsParser
import com.example.domain.RootStatus
import com.example.domain.SourcedValue
import com.example.domain.StorageTelemetry
import com.example.domain.ThermalTelemetry
import com.example.domain.ZramParser
import com.example.root.RootCommandAllowlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareCalculatorsTest {

    @Test
    fun `parseTimeInState computes MHz, ms, and percentage residency`() {
        val raw = """
            960000 3000
            1094400 1000
            1401600 6000
        """.trimIndent()
        val entries = CpuFreqParser.parseTimeInState(raw)
        assertEquals(3, entries.size)
        assertEquals(960.0, entries[0].freqMhz, 0.01)
        assertEquals(30_000L, entries[0].timeMs)
        assertEquals(30.0, entries[0].percent, 0.01)
        assertEquals(1401.6, entries[2].freqMhz, 0.01)
        assertEquals(60.0, entries[2].percent, 0.01)
    }

    @Test
    fun `ZramParser parses mm_stat compression ratio accurately`() {
        val raw = "536870912 134217728 140000000 0 134217728 1024 0 0"
        val stats = ZramParser.parseMmStat(raw)
        assertNotNull(stats)
        assertEquals(536870912L, stats!!.origDataBytes)
        assertEquals(134217728L, stats.comprDataBytes)
        assertEquals(140000000L, stats.memUsedTotalBytes)
        assertEquals(4.0, stats.ratio!!, 0.01)
    }

    @Test
    fun `PartitionsParser skips header and extracts block partitions`() {
        val raw = """
            major minor  #blocks  name

             179        0   61071360 mmcblk0
             179       48   52428800 mmcblk0p48
             253        0    2097152 zram0
        """.trimIndent()
        val list = PartitionsParser.parseProcPartitions(raw)
        assertEquals(3, list.size)
        assertEquals("mmcblk0", list[0].name)
        assertEquals(61071360L, list[0].blocksKb)
        assertEquals(253, list[2].major)
        assertEquals("zram0", list[2].name)
    }

    @Test
    fun `EmmcHealthParser decodes JEDEC life_time and pre_eol_info`() {
        assertEquals("0% - 10% wear used", EmmcHealthParser.decodeLifeTimeHex("0x01"))
        assertEquals("10% - 20% wear used", EmmcHealthParser.decodeLifeTimeHex("0x02"))
        assertEquals(">100% (Exceeded max estimated life)", EmmcHealthParser.decodeLifeTimeHex("0x0B"))

        val (normalMsg, normalSev) = EmmcHealthParser.decodePreEol("0x01")
        assertEquals(HealthSeverity.NORMAL, normalSev)
        assertTrue(normalMsg!!.contains("Normal"))

        val (urgMsg, urgSev) = EmmcHealthParser.decodePreEol("0x03")
        assertEquals(HealthSeverity.CRITICAL, urgSev)
        assertTrue(urgMsg!!.contains("Urgent"))
    }

    @Test
    fun `DrainRateCalculator estimates percent per hour and remaining time`() {
        val drainRate = DrainRateCalculator.estimatePctPerHourFromCurrent(
            currentMa = -400.0,
            isCharging = false,
            capacityMah = 4000.0
        )
        assertEquals(-10.0, drainRate!!, 0.01)
        assertEquals("Est. empty in 5h 0m", DrainRateCalculator.estimateRemainingTimeLabel(50, false, drainRate))

        val chargeRate = DrainRateCalculator.estimatePctPerHourFromCurrent(
            currentMa = 2000.0,
            isCharging = true,
            capacityMah = 4000.0
        )
        assertEquals(50.0, chargeRate!!, 0.01)
        assertEquals("Est. full in 1h 0m", DrainRateCalculator.estimateRemainingTimeLabel(50, true, chargeRate))
    }

    @Test
    fun `RootCommandAllowlist allows safe v2 sysfs paths and blocks writes`() {
        assertTrue(RootCommandAllowlist.isAllowedCommand("cat /proc/sys/vm/swappiness"))
        assertTrue(RootCommandAllowlist.isAllowedCommand("cat /sys/block/mmcblk0/device/life_time"))
        assertTrue(RootCommandAllowlist.isAllowedCommand("cat /sys/module/workqueue/parameters/power_efficient"))
        assertFalse(RootCommandAllowlist.isAllowedCommand("echo 60 > /proc/sys/vm/swappiness"))
        assertFalse(RootCommandAllowlist.isAllowedCommand("cat /proc/sys/vm/swappiness; rm -rf /"))
        assertFalse(RootCommandAllowlist.isAllowedCommand("cat /data/local/tmp/secret"))
    }

    @Test
    fun `DiagnosticEngine formatAsJson outputs valid structured telemetry`() {
        val snapshot = HardwareSnapshot(
            timestampMs = 1_700_000_000_000L,
            battery = BatteryTelemetry(
                percentage = SourcedValue(80, "%", DataSourceOrigin.SYSFS_BATTERY),
                voltageMv = SourcedValue(4120.0, "mV", DataSourceOrigin.SYSFS_BATTERY),
                currentNowMa = SourcedValue(-320.0, "mA", DataSourceOrigin.SYSFS_BATTERY),
                currentAvgMa = SourcedValue(-300.0, "mA", DataSourceOrigin.SYSFS_BATTERY),
                currentDirectionNote = "Discharging",
                temperatureC = SourcedValue(33.5, "°C", DataSourceOrigin.SYSFS_BATTERY),
                status = SourcedValue("Discharging", "", DataSourceOrigin.SYSFS_BATTERY),
                chargingState = "Discharging",
                healthAndroid = SourcedValue("Good", "", DataSourceOrigin.ANDROID_BATTERY_MANAGER),
                technology = SourcedValue("Li-poly", "", DataSourceOrigin.ANDROID_BATTERY_MANAGER),
                chargeCounterMah = SourcedValue(3200.0, "mAh", DataSourceOrigin.SYSFS_BATTERY),
                chargeFullMah = SourcedValue(3850.0, "mAh", DataSourceOrigin.SYSFS_BATTERY),
                chargeFullDesignMah = SourcedValue(4100.0, "mAh", DataSourceOrigin.SYSFS_BATTERY),
                cycleCount = SourcedValue(120, "cycles", DataSourceOrigin.SYSFS_BATTERY),
                estimatedHealth = BatteryHealthEstimator.estimate(3850.0, 4100.0),
                detectedPowerSupplies = emptyList(),
                severity = HealthSeverity.NORMAL
            ),
            charging = ChargingTelemetry(
                isChargerConnected = false,
                isCharging = false,
                chargingSource = "Disconnected",
                chargingSpeedLabel = "Not charging",
                usbVoltageMv = SourcedValue(null, "mV", DataSourceOrigin.UNAVAILABLE),
                usbCurrentMa = SourcedValue(null, "mA", DataSourceOrigin.UNAVAILABLE),
                usbPowerWatts = SourcedValue(null, "W", DataSourceOrigin.UNAVAILABLE),
                batteryVoltageMv = SourcedValue(4120.0, "mV", DataSourceOrigin.SYSFS_BATTERY),
                batteryCurrentMa = SourcedValue(-320.0, "mA", DataSourceOrigin.SYSFS_BATTERY),
                batteryPowerWatts = SourcedValue(1.32, "W", DataSourceOrigin.SYSFS_BATTERY),
                chargingTemperatureC = SourcedValue(33.5, "°C", DataSourceOrigin.SYSFS_BATTERY),
                activeSession = null,
                sessionHistory = emptyList()
            ),
            thermal = ThermalTelemetry(
                zones = emptyList(),
                batteryTempC = 33.5,
                cpuTempC = 44.0,
                gpuTempC = 42.0,
                socTempC = 45.0,
                maxZoneTempC = 45.0,
                maxRecordedTempC = 45.0,
                osThermalStatusCode = 0,
                osThermalStatusLabel = "NONE",
                osThermalHeadroom = 0.8f,
                overallSeverity = HealthSeverity.NORMAL
            ),
            cpu = CpuTelemetry(
                modelName = "Snapdragon 435",
                hardwarePlatform = "MSM8940",
                architecture = "ARMv8",
                totalCores = 8,
                onlineCores = 8,
                totalUtilizationPercent = 18.0,
                loadAvg1m = 0.7,
                loadAvg5m = 0.6,
                loadAvg15m = 0.5,
                cores = emptyList(),
                availableGovernors = listOf("interactive"),
                availableFrequenciesMhz = listOf(960.0, 1401.6),
                source = DataSourceOrigin.PROCFS,
                totalElapsedMs = 100_000L,
                awakeUptimeMs = 25_000L,
                deepSleepMs = 75_000L,
                deepSleepPercent = 75.0
            ),
            gpu = GpuTelemetry(
                gpuName = "Adreno 505",
                vendor = "Qualcomm",
                glEsVersion = "3.2",
                currentFreqMhz = 300.0,
                maxFreqMhz = 450.0,
                minFreqMhz = 133.0,
                utilizationPercent = 10.0,
                governor = "msm-adreno-tz",
                temperatureC = 42.0,
                availableFrequenciesMhz = listOf(133.0, 300.0, 450.0),
                sysfsNodePath = "/sys/class/kgsl/kgsl-3d0",
                source = DataSourceOrigin.SYSFS_KGSL,
                isSysfsAvailable = true
            ),
            memory = MemoryTelemetry(
                totalRamBytes = 4_000_000_000L,
                availableRamBytes = 2_000_000_000L,
                usedRamBytes = 2_000_000_000L,
                freeRamBytes = 500_000_000L,
                cachedRamBytes = 800_000_000L,
                buffersBytes = 50_000_000L,
                swapTotalBytes = 1_000_000_000L,
                swapFreeBytes = 750_000_000L,
                utilizationPercent = 50.0,
                isLowMemory = false,
                thresholdBytes = 200_000_000L,
                interpretation = "Nominal",
                sources = listOf(DataSourceOrigin.PROCFS),
                zramCompressionRatio = 3.2
            ),
            storage = StorageTelemetry(
                internalTotalBytes = 50_000_000_000L,
                internalUsedBytes = 20_000_000_000L,
                internalFreeBytes = 30_000_000_000L,
                usagePercent = 40.0,
                primaryFsType = "ext4",
                mounts = emptyList(),
                blockDevices = emptyList(),
                diagnosticNote = "Nominal"
            ),
            sensors = emptyList(),
            kernelSystem = KernelSystemInfo(
                deviceModel = "Xiaomi Redmi 4X",
                manufacturer = "Xiaomi",
                deviceCodename = "santoni",
                board = "QC_Reference_Phone",
                hardware = "qcom",
                androidVersion = "11",
                sdkInt = 30,
                securityPatch = "2023-05-01",
                buildFingerprint = "santoni",
                supportedAbis = listOf("arm64-v8a"),
                kernelVersion = "4.9.337",
                linuxProcVersion = "Linux 4.9.337",
                selinuxStatus = "Enforcing",
                rootStatus = RootStatus.ROOT_AVAILABLE,
                rootProbeDetail = "uid=0(root)",
                magiskVersion = "27.0",
                availableGovernors = listOf("interactive"),
                availableCpuFrequenciesMhz = listOf(960.0),
                thermalConfigPreview = null
            )
        )
        val report = DiagnosticEngine.runDiagnostics(snapshot)
        val jsonStr = DiagnosticEngine.formatAsJson(report, snapshot)
        assertTrue(jsonStr.contains("\"appVersion\": \"2.0.0\""))
        assertTrue(jsonStr.contains("\"deepSleepPercent\": 75"))
        assertTrue(jsonStr.contains("\"zramCompressionRatio\": 3.2"))
    }
}
