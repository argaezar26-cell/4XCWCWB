package com.example

import com.example.diagnostics.DiagnosticEngine
import com.example.domain.BatteryHealthEstimator
import com.example.domain.BatteryTelemetry
import com.example.domain.ChargingTelemetry
import com.example.domain.CpuFreqParser
import com.example.domain.CpuTelemetry
import com.example.domain.DataSourceOrigin
import com.example.domain.DiagnosticStatus
import com.example.domain.GpuTelemetry
import com.example.domain.HardwareSnapshot
import com.example.domain.HealthSeverity
import com.example.domain.KernelSystemInfo
import com.example.domain.MemoryTelemetry
import com.example.domain.PowerCalculator
import com.example.domain.RootStatus
import com.example.domain.SourcedValue
import com.example.domain.StorageTelemetry
import com.example.domain.ThermalParser
import com.example.domain.ThermalTelemetry
import com.example.domain.ThermalZoneCategory
import com.example.domain.UnitConverter
import com.example.root.RootCommandAllowlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareCalculatorsTest {

    @Test
    fun `microampere to milliampere conversion handles signed uA and mA`() {
        val chargingUa = UnitConverter.toMilliamperes(1_850_000L)
        assertEquals(1850.0, chargingUa.value, 0.01)
        assertEquals("µA", chargingUa.detectedSourceUnit)

        val dischargingUa = UnitConverter.toMilliamperes(-420_000L)
        assertEquals(-420.0, dischargingUa.value, 0.01)
        assertEquals("µA", dischargingUa.detectedSourceUnit)

        val directMa = UnitConverter.toMilliamperes(-350L)
        assertEquals(-350.0, directMa.value, 0.01)
        assertEquals("mA", directMa.detectedSourceUnit)

        val zeroMa = UnitConverter.toMilliamperes(0L)
        assertEquals(0.0, zeroMa.value, 0.01)
    }

    @Test
    fun `microvolt to millivolt conversion handles uV mV and invalid values`() {
        val battUv = UnitConverter.toMillivolts(4_185_000L)
        assertNotNull(battUv)
        assertEquals(4185.0, battUv!!.value, 0.01)
        assertEquals("µV", battUv.detectedSourceUnit)

        val usbUv = UnitConverter.toMillivolts(5_020_000L)
        assertNotNull(usbUv)
        assertEquals(5020.0, usbUv!!.value, 0.01)

        val bmMv = UnitConverter.toMillivolts(3_920L)
        assertNotNull(bmMv)
        assertEquals(3920.0, bmMv!!.value, 0.01)
        assertEquals("mV", bmMv.detectedSourceUnit)

        assertNull(UnitConverter.toMillivolts(0L))
        assertNull(UnitConverter.toMillivolts(-100L))
    }

    @Test
    fun `power calculation P equals V times I accurately`() {
        // 5000 mV (5.0V) * 1800 mA (1.8A) = 9.0 W
        val usbPower = PowerCalculator.calculatePowerWatts(5000.0, 1800.0)
        assertNotNull(usbPower)
        assertEquals(9.0, usbPower!!, 0.001)

        // Negative battery current (Qualcomm charging sign convention) -> positive magnitude 7.6 W
        val battPower = PowerCalculator.calculatePowerWatts(3800.0, -2000.0)
        assertNotNull(battPower)
        assertEquals(7.6, battPower!!, 0.001)

        // Missing rail value must return null (never fabricate)
        assertNull(PowerCalculator.calculatePowerWatts(null, 1500.0))
        assertNull(PowerCalculator.calculatePowerWatts(5000.0, null))
    }

    @Test
    fun `battery health estimation computes ratio with safety bounds`() {
        // 3690 mAh / 4100 mAh = 90.0%
        val est = BatteryHealthEstimator.estimate(3690.0, 4100.0)
        assertNotNull(est)
        assertEquals(90.0, est!!.estimatedPercent, 0.01)
        assertTrue(est.disclaimer.contains("Estimated", ignoreCase = true))

        // Slightly over design capacity clamps to 100%
        val overEst = BatteryHealthEstimator.estimate(4200.0, 4100.0)
        assertNotNull(overEst)
        assertEquals(100.0, overEst!!.estimatedPercent, 0.01)

        // Missing or zero values return null
        assertNull(BatteryHealthEstimator.estimate(null, 4100.0))
        assertNull(BatteryHealthEstimator.estimate(3500.0, 0.0))
    }

    @Test
    fun `thermal sensor parsing handles millidegree decidegree and sentinel values`() {
        val cpuZone = ThermalParser.parseTemperatureCelsius(46_500L)
        assertNotNull(cpuZone)
        assertEquals(46.5, cpuZone!!.celsius, 0.01)
        assertEquals("m°C", cpuZone.detectedUnit)

        val battZone = ThermalParser.parseTemperatureCelsius(348L, isBatteryPowerSupplyTemp = true)
        assertNotNull(battZone)
        assertEquals(34.8, battZone!!.celsius, 0.01)
        assertEquals("0.1°C", battZone.detectedUnit)

        // Disconnected / sentinel values rejected
        assertNull(ThermalParser.parseTemperatureCelsius(-273_000L))
        assertNull(ThermalParser.parseTemperatureCelsius(-40_000L))
        assertNull(ThermalParser.parseTemperatureCelsius(255_000L))

        assertEquals(ThermalZoneCategory.CPU, ThermalParser.classifyZone("tsens_tz_sensor4"))
        assertEquals(ThermalZoneCategory.BATTERY, ThermalParser.classifyZone("battery"))
        assertEquals(ThermalZoneCategory.GPU, ThermalParser.classifyZone("gpu-usr"))
    }

    @Test
    fun `cpu frequency conversion and proc stat parsing work accurately`() {
        val khz = UnitConverter.frequencyToMhz(1_401_600L)
        assertNotNull(khz)
        assertEquals(1401.6, khz!!.value, 0.01)
        assertEquals("kHz", khz.detectedSourceUnit)

        val hz = UnitConverter.frequencyToMhz(450_000_000L)
        assertNotNull(hz)
        assertEquals(450.0, hz!!.value, 0.01)
        assertEquals("Hz", hz.detectedSourceUnit)

        val freqs = CpuFreqParser.parseFrequencyListMhz("960000 1094400 1401600")
        assertEquals(listOf(960.0, 1094.4, 1401.6), freqs)

        val s1 = CpuFreqParser.parseProcStat("cpu  100 0 50 850 0 0 0 0")
        val s2 = CpuFreqParser.parseProcStat("cpu  200 0 100 1700 0 0 0 0")
        val util = CpuFreqParser.computeUtilizationPercent(s1["cpu"], s2["cpu"])
        assertNotNull(util)
        assertEquals(15.0, util!!, 0.1)
    }

    @Test
    fun `root command allowlist strictly blocks writes and arbitrary commands`() {
        assertTrue(RootCommandAllowlist.isAllowedCommand("id"))
        assertTrue(RootCommandAllowlist.isAllowedCommand("cat /sys/class/power_supply/battery/charge_full"))
        assertTrue(RootCommandAllowlist.isAllowedCommand("ls /sys/class/thermal"))

        assertFalse(RootCommandAllowlist.isAllowedCommand("echo 1 > /sys/class/power_supply/battery/charging_enabled"))
        assertFalse(RootCommandAllowlist.isAllowedCommand("cat /sys/class/thermal/thermal_zone0/temp; rm -rf /"))
        assertFalse(RootCommandAllowlist.isAllowedCommand("cat /data/local/tmp/secret"))
        assertFalse(RootCommandAllowlist.isAllowedCommand("cat /sys/class/thermal/../../etc/passwd"))
        assertFalse(RootCommandAllowlist.isAllowedCommand("reboot"))
    }

    @Test
    fun `diagnostic engine handles unavailable optional nodes without reporting hardware error`() {
        val snapshot = HardwareSnapshot(
            timestampMs = 1_700_000_000_000L,
            battery = BatteryTelemetry(
                percentage = SourcedValue(82, "%", DataSourceOrigin.ANDROID_BATTERY_MANAGER),
                voltageMv = SourcedValue(4120.0, "mV", DataSourceOrigin.SYSFS_BATTERY),
                currentNowMa = SourcedValue(-320.0, "mA", DataSourceOrigin.SYSFS_BATTERY),
                currentAvgMa = SourcedValue(null, "mA", DataSourceOrigin.UNAVAILABLE),
                currentDirectionNote = "Discharging",
                temperatureC = SourcedValue(33.5, "°C", DataSourceOrigin.SYSFS_BATTERY),
                status = SourcedValue("Discharging", "", DataSourceOrigin.ANDROID_BATTERY_MANAGER),
                chargingState = "Discharging",
                healthAndroid = SourcedValue("Good", "", DataSourceOrigin.ANDROID_BATTERY_MANAGER),
                technology = SourcedValue("Li-poly", "", DataSourceOrigin.ANDROID_BATTERY_MANAGER),
                chargeCounterMah = SourcedValue(3300.0, "mAh", DataSourceOrigin.SYSFS_BATTERY),
                chargeFullMah = SourcedValue(3850.0, "mAh", DataSourceOrigin.SYSFS_BATTERY),
                chargeFullDesignMah = SourcedValue(4100.0, "mAh", DataSourceOrigin.SYSFS_BATTERY),
                cycleCount = SourcedValue(null, "cycles", DataSourceOrigin.UNAVAILABLE),
                estimatedHealth = BatteryHealthEstimator.estimate(3850.0, 4100.0),
                detectedPowerSupplies = emptyList(),
                severity = HealthSeverity.NORMAL
            ),
            charging = ChargingTelemetry(
                isChargerConnected = false,
                isCharging = false,
                chargingSource = "Disconnected (Battery)",
                chargingSpeedLabel = "Not charging",
                usbVoltageMv = SourcedValue(null, "mV", DataSourceOrigin.UNAVAILABLE),
                usbCurrentMa = SourcedValue(null, "mA", DataSourceOrigin.UNAVAILABLE),
                usbPowerWatts = SourcedValue(null, "W", DataSourceOrigin.UNAVAILABLE),
                batteryVoltageMv = SourcedValue(4120.0, "mV", DataSourceOrigin.SYSFS_BATTERY),
                batteryCurrentMa = SourcedValue(-320.0, "mA", DataSourceOrigin.SYSFS_BATTERY),
                batteryPowerWatts = SourcedValue(1.318, "W", DataSourceOrigin.SYSFS_BATTERY),
                chargingTemperatureC = SourcedValue(33.5, "°C", DataSourceOrigin.SYSFS_BATTERY),
                activeSession = null,
                sessionHistory = emptyList()
            ),
            thermal = ThermalTelemetry(
                zones = emptyList(),
                batteryTempC = 33.5,
                cpuTempC = null,
                gpuTempC = null,
                socTempC = null,
                maxZoneTempC = 33.5,
                maxRecordedTempC = 33.5,
                osThermalStatusCode = 0,
                osThermalStatusLabel = "NONE (Nominal)",
                osThermalHeadroom = null,
                overallSeverity = HealthSeverity.NORMAL
            ),
            cpu = CpuTelemetry(
                modelName = "ARMv8 Processor rev 4",
                hardwarePlatform = "Qualcomm Technologies, Inc MSM8940",
                architecture = "ARMv8",
                totalCores = 8,
                onlineCores = 8,
                totalUtilizationPercent = 18.4,
                loadAvg1m = 1.2,
                loadAvg5m = 1.0,
                loadAvg15m = 0.9,
                cores = emptyList(),
                availableGovernors = listOf("interactive", "ondemand"),
                availableFrequenciesMhz = listOf(960.0, 1401.6),
                source = DataSourceOrigin.PROCFS
            ),
            gpu = GpuTelemetry(
                gpuName = "Adreno (TM) 505",
                vendor = "Qualcomm",
                glEsVersion = "3.2",
                currentFreqMhz = null,
                maxFreqMhz = null,
                minFreqMhz = null,
                utilizationPercent = null,
                governor = null,
                temperatureC = null,
                availableFrequenciesMhz = emptyList(),
                sysfsNodePath = null,
                source = DataSourceOrigin.EGL_DRIVER,
                isSysfsAvailable = false
            ),
            memory = MemoryTelemetry(
                totalRamBytes = 4L * 1024 * 1024 * 1024,
                availableRamBytes = 2L * 1024 * 1024 * 1024,
                usedRamBytes = 2L * 1024 * 1024 * 1024,
                freeRamBytes = 512L * 1024 * 1024,
                cachedRamBytes = 800L * 1024 * 1024,
                buffersBytes = 64L * 1024 * 1024,
                swapTotalBytes = 1024L * 1024 * 1024,
                swapFreeBytes = 900L * 1024 * 1024,
                utilizationPercent = 50.0,
                isLowMemory = false,
                thresholdBytes = 256L * 1024 * 1024,
                interpretation = "Healthy RAM headroom",
                sources = listOf(DataSourceOrigin.ACTIVITY_MANAGER)
            ),
            storage = StorageTelemetry(
                internalTotalBytes = 54L * 1024 * 1024 * 1024,
                internalUsedBytes = 20L * 1024 * 1024 * 1024,
                internalFreeBytes = 34L * 1024 * 1024 * 1024,
                usagePercent = 37.0,
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
                buildFingerprint = "xiaomi/santoni/santoni:11/...",
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
                kernelVersion = "4.9.337-perf+",
                linuxProcVersion = "Linux version 4.9.337",
                selinuxStatus = "Enforcing",
                rootStatus = RootStatus.ROOT_NOT_FOUND,
                rootProbeDetail = "su binary not found",
                magiskVersion = null,
                availableGovernors = listOf("interactive"),
                availableCpuFrequenciesMhz = listOf(960.0, 1401.6),
                thermalConfigPreview = null
            )
        )

        val report = DiagnosticEngine.runDiagnostics(snapshot)
        assertEquals(0, report.errorCount)
        assertEquals(DiagnosticStatus.UNAVAILABLE, report.checks.first { it.id == "gpu" }.status)
        assertEquals(DiagnosticStatus.UNAVAILABLE, report.checks.first { it.id == "root" }.status)

        val txt = DiagnosticEngine.formatAsText(report, snapshot)
        assertTrue(txt.contains("REDMI 4X HARDWARE LAB"))
        assertTrue(txt.contains("santoni"))

        val csv = DiagnosticEngine.formatAsCsv(report, snapshot)
        assertTrue(csv.startsWith("timestamp_ms,device,check_id"))
        assertTrue(csv.contains("\"battery\""))
    }
}
