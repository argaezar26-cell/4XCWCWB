package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.domain.BatteryHealthEstimator
import com.example.domain.BatteryTelemetry
import com.example.domain.ChargingTelemetry
import com.example.domain.CpuTelemetry
import com.example.domain.DataSourceOrigin
import com.example.domain.GpuTelemetry
import com.example.domain.HardwareSnapshot
import com.example.domain.HealthSeverity
import com.example.domain.KernelSystemInfo
import com.example.domain.MemoryTelemetry
import com.example.domain.RootStatus
import com.example.domain.SourcedValue
import com.example.domain.StorageTelemetry
import com.example.domain.TelemetryHistoryState
import com.example.domain.ThermalTelemetry
import com.example.monitoring.HardwareLabUiState
import com.example.monitoring.LabPage
import com.example.ui.HardwareLabApp
import com.example.ui.theme.HardwareLabTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `read string from context matches Redmi 4X Hardware Lab`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Redmi 4X Hardware Lab", appName)
    }

    @Test
    fun `hardware lab UI renders overview and switches to diagnostics`() {
        var currentPage by androidx.compose.runtime.mutableStateOf(LabPage.OVERVIEW)
        val snapshot = createTestSnapshot()

        composeTestRule.setContent {
            HardwareLabTheme {
                HardwareLabApp(
                    state = HardwareLabUiState(
                        isLoadingInitial = false,
                        currentPage = currentPage,
                        snapshot = snapshot,
                        history = TelemetryHistoryState()
                    ),
                    onSelectPage = { currentPage = it },
                    onSelectInterval = {},
                    onTogglePause = {},
                    onResetStats = {},
                    onToggleBackground = {},
                    onReProbeRoot = {},
                    onRunBenchmark = {},
                    onToggleSensorLiveStream = {},
                    onRunDiagnostics = {},
                    onBuildTextReport = { "Report" },
                    onBuildCsvReport = { "csv" }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("overview_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_nav_diagnostics").performClick()
        composeTestRule.waitForIdle()
        assertEquals(LabPage.DIAGNOSTICS, currentPage)
        composeTestRule.onNodeWithTag("diagnostics_screen").assertIsDisplayed()
    }

    @Test
    fun `MainActivity boots and initializes ViewModel without crashing`() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertEquals("Redmi 4X Hardware Lab", activity.title.toString().ifEmpty { activity.getString(R.string.app_name) })
        controller.pause().stop().destroy()
    }

    private fun createTestSnapshot() = HardwareSnapshot(
        timestampMs = 1_700_000_000_000L,
        battery = BatteryTelemetry(
            percentage = SourcedValue(75, "%", DataSourceOrigin.SYSFS_BATTERY),
            voltageMv = SourcedValue(4050.0, "mV", DataSourceOrigin.SYSFS_BATTERY),
            currentNowMa = SourcedValue(-280.0, "mA", DataSourceOrigin.SYSFS_BATTERY),
            currentAvgMa = SourcedValue(-250.0, "mA", DataSourceOrigin.SYSFS_BATTERY),
            currentDirectionNote = "Discharging",
            temperatureC = SourcedValue(32.0, "°C", DataSourceOrigin.SYSFS_BATTERY),
            status = SourcedValue("Discharging", "", DataSourceOrigin.SYSFS_BATTERY),
            chargingState = "Discharging",
            healthAndroid = SourcedValue("Good", "", DataSourceOrigin.ANDROID_BATTERY_MANAGER),
            technology = SourcedValue("Li-poly", "", DataSourceOrigin.ANDROID_BATTERY_MANAGER),
            chargeCounterMah = SourcedValue(3000.0, "mAh", DataSourceOrigin.SYSFS_BATTERY),
            chargeFullMah = SourcedValue(3800.0, "mAh", DataSourceOrigin.SYSFS_BATTERY),
            chargeFullDesignMah = SourcedValue(4100.0, "mAh", DataSourceOrigin.SYSFS_BATTERY),
            cycleCount = SourcedValue(210, "cycles", DataSourceOrigin.SYSFS_BATTERY),
            estimatedHealth = BatteryHealthEstimator.estimate(3800.0, 4100.0),
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
            batteryVoltageMv = SourcedValue(4050.0, "mV", DataSourceOrigin.SYSFS_BATTERY),
            batteryCurrentMa = SourcedValue(-280.0, "mA", DataSourceOrigin.SYSFS_BATTERY),
            batteryPowerWatts = SourcedValue(1.13, "W", DataSourceOrigin.SYSFS_BATTERY),
            chargingTemperatureC = SourcedValue(32.0, "°C", DataSourceOrigin.SYSFS_BATTERY),
            activeSession = null,
            sessionHistory = emptyList()
        ),
        thermal = ThermalTelemetry(
            zones = emptyList(),
            batteryTempC = 32.0,
            cpuTempC = 45.0,
            gpuTempC = 43.0,
            socTempC = 46.0,
            maxZoneTempC = 46.0,
            maxRecordedTempC = 46.0,
            osThermalStatusCode = 0,
            osThermalStatusLabel = "NONE (Nominal)",
            osThermalHeadroom = 0.9f,
            overallSeverity = HealthSeverity.NORMAL
        ),
        cpu = CpuTelemetry(
            modelName = "Qualcomm Snapdragon 435",
            hardwarePlatform = "MSM8940",
            architecture = "ARMv8",
            totalCores = 8,
            onlineCores = 8,
            totalUtilizationPercent = 22.0,
            loadAvg1m = 1.1,
            loadAvg5m = 0.9,
            loadAvg15m = 0.8,
            cores = emptyList(),
            availableGovernors = listOf("interactive"),
            availableFrequenciesMhz = listOf(960.0, 1401.6),
            source = DataSourceOrigin.PROCFS
        ),
        gpu = GpuTelemetry(
            gpuName = "Adreno (TM) 505",
            vendor = "Qualcomm",
            glEsVersion = "3.2",
            currentFreqMhz = 300.0,
            maxFreqMhz = 450.0,
            minFreqMhz = 133.0,
            utilizationPercent = 12.0,
            governor = "msm-adreno-tz",
            temperatureC = 42.0,
            availableFrequenciesMhz = listOf(133.0, 300.0, 450.0),
            sysfsNodePath = "/sys/class/kgsl/kgsl-3d0",
            source = DataSourceOrigin.SYSFS_KGSL,
            isSysfsAvailable = true
        ),
        memory = MemoryTelemetry(
            totalRamBytes = 4L * 1024 * 1024 * 1024,
            availableRamBytes = 2L * 1024 * 1024 * 1024,
            usedRamBytes = 2L * 1024 * 1024 * 1024,
            freeRamBytes = 400L * 1024 * 1024,
            cachedRamBytes = 900L * 1024 * 1024,
            buffersBytes = 50L * 1024 * 1024,
            swapTotalBytes = 1024L * 1024 * 1024,
            swapFreeBytes = 800L * 1024 * 1024,
            utilizationPercent = 50.0,
            isLowMemory = false,
            thresholdBytes = 200L * 1024 * 1024,
            interpretation = "Healthy RAM headroom",
            sources = listOf(DataSourceOrigin.ACTIVITY_MANAGER, DataSourceOrigin.PROCFS)
        ),
        storage = StorageTelemetry(
            internalTotalBytes = 54L * 1024 * 1024 * 1024,
            internalUsedBytes = 18L * 1024 * 1024 * 1024,
            internalFreeBytes = 36L * 1024 * 1024 * 1024,
            usagePercent = 33.3,
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
            kernelVersion = "4.9.337",
            linuxProcVersion = "Linux version 4.9.337",
            selinuxStatus = "Enforcing",
            rootStatus = RootStatus.ROOT_AVAILABLE,
            rootProbeDetail = "uid=0(root)",
            magiskVersion = "27.0 (27000)",
            availableGovernors = listOf("interactive"),
            availableCpuFrequenciesMhz = listOf(960.0, 1401.6),
            thermalConfigPreview = null
        )
    )
}
