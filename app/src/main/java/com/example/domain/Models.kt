package com.example.domain

enum class HealthSeverity {
    NORMAL,
    WARNING,
    CRITICAL,
    UNAVAILABLE
}

enum class DataSourceOrigin(val label: String) {
    ANDROID_BATTERY_MANAGER("Android BatteryManager"),
    SYSFS_BATTERY("Sysfs battery"),
    SYSFS_USB("Sysfs USB"),
    SYSFS_POWER_SUPPLY("Sysfs power_supply"),
    SYSFS_THERMAL("Sysfs thermal"),
    POWER_MANAGER("Android PowerManager"),
    PROCFS("Procfs"),
    SYSFS_CPU("Sysfs cpufreq"),
    SYSFS_KGSL("Sysfs KGSL"),
    SYSFS_DEVFREQ("Sysfs devfreq"),
    EGL_DRIVER("OpenGL ES Driver"),
    ACTIVITY_MANAGER("Android ActivityManager"),
    STAT_FS("Android StatFs"),
    SYSFS_BLOCK("Sysfs block"),
    SYSFS_MMC("Sysfs JEDEC eMMC"),
    SENSOR_MANAGER("Android SensorManager"),
    DISPLAY_MANAGER("Android Display"),
    ROOT_KERNEL_INTERFACE("Root-accessible kernel interface"),
    SYSTEM_BUILD("Android Build / OS"),
    UNAVAILABLE("Unavailable")
}

enum class RootStatus(val label: String) {
    ROOT_AVAILABLE("ROOT AVAILABLE"),
    ROOT_DENIED("ROOT DENIED"),
    ROOT_NOT_FOUND("ROOT NOT FOUND")
}

enum class DiagnosticStatus {
    PASS,
    WARNING,
    UNAVAILABLE,
    ERROR
}

enum class RefreshInterval(val seconds: Int, val label: String) {
    ONE_SEC(1, "1s"),
    TWO_SEC(2, "2s"),
    FIVE_SEC(5, "5s"),
    TEN_SEC(10, "10s")
}

data class SourcedValue<out T>(
    val value: T?,
    val unit: String,
    val source: DataSourceOrigin,
    val nodePath: String? = null,
    val note: String? = null
)

data class PowerSupplyNodeInfo(
    val name: String,
    val path: String,
    val type: String?,
    val online: Boolean?,
    val present: Boolean?,
    val status: String?,
    val health: String?,
    val technology: String?,
    val capacityPercent: Int?,
    val voltageMv: Double?,
    val currentMa: Double?,
    val currentAvgMa: Double?,
    val inputCurrentMa: Double?,
    val tempCelsius: Double?,
    val chargeCounterMah: Double?,
    val chargeFullMah: Double?,
    val chargeFullDesignMah: Double?,
    val cycleCount: Int?,
    val voltageMaxMv: Double?,
    val currentMaxMa: Double?,
    val chargeType: String? = null,
    val resistanceMohm: Double? = null,
    val sourceOrigin: DataSourceOrigin
)

data class BatteryHealthEstimation(
    val estimatedPercent: Double,
    val chargeFullMah: Double,
    val chargeFullDesignMah: Double,
    val disclaimer: String = "Estimated from kernel charge_full / charge_full_design; not a laboratory capacity test."
)

data class BatteryTelemetry(
    val percentage: SourcedValue<Int>,
    val voltageMv: SourcedValue<Double>,
    val currentNowMa: SourcedValue<Double>,
    val currentAvgMa: SourcedValue<Double>,
    val currentDirectionNote: String,
    val temperatureC: SourcedValue<Double>,
    val status: SourcedValue<String>,
    val chargingState: String,
    val healthAndroid: SourcedValue<String>,
    val technology: SourcedValue<String>,
    val chargeCounterMah: SourcedValue<Double>,
    val chargeFullMah: SourcedValue<Double>,
    val chargeFullDesignMah: SourcedValue<Double>,
    val cycleCount: SourcedValue<Int>,
    val estimatedHealth: BatteryHealthEstimation?,
    val detectedPowerSupplies: List<PowerSupplyNodeInfo>,
    val severity: HealthSeverity,
    val chargeType: SourcedValue<String> = SourcedValue(null, "", DataSourceOrigin.UNAVAILABLE),
    val internalResistanceMohm: SourcedValue<Double> = SourcedValue(null, "mΩ", DataSourceOrigin.UNAVAILABLE),
    val voltageMaxDesignMv: SourcedValue<Double> = SourcedValue(null, "mV", DataSourceOrigin.UNAVAILABLE),
    val drainOrChargeRatePctPerHour: Double? = null,
    val estimatedTimeLabel: String? = null
)

data class ChargingSessionRecord(
    val id: Long,
    val startTimeMs: Long,
    val endTimeMs: Long?,
    val source: String,
    val startPercent: Int?,
    val latestPercent: Int?,
    val peakBatteryPowerWatts: Double?,
    val peakUsbPowerWatts: Double?,
    val maxTempCelsius: Double?
)

data class ChargingTelemetry(
    val isChargerConnected: Boolean,
    val isCharging: Boolean,
    val chargingSource: String,
    val chargingSpeedLabel: String,
    val usbVoltageMv: SourcedValue<Double>,
    val usbCurrentMa: SourcedValue<Double>,
    val usbPowerWatts: SourcedValue<Double>,
    val batteryVoltageMv: SourcedValue<Double>,
    val batteryCurrentMa: SourcedValue<Double>,
    val batteryPowerWatts: SourcedValue<Double>,
    val chargingTemperatureC: SourcedValue<Double>,
    val activeSession: ChargingSessionRecord?,
    val sessionHistory: List<ChargingSessionRecord>
)

enum class ThermalZoneCategory(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
    SOC("SoC"),
    BATTERY("Battery"),
    PMIC_BOARD("PMIC / Board"),
    OTHER("Other")
}

data class ThermalTripPoint(
    val index: Int,
    val type: String?,
    val tempCelsius: Double?
)

data class ThermalZoneReading(
    val zoneName: String,
    val path: String,
    val type: String,
    val category: ThermalZoneCategory,
    val temperatureC: Double?,
    val rawValue: Long?,
    val detectedUnit: String,
    val mode: String?,
    val policy: String?,
    val source: DataSourceOrigin,
    val severity: HealthSeverity,
    val tripPoints: List<ThermalTripPoint> = emptyList()
)

data class ThermalTelemetry(
    val zones: List<ThermalZoneReading>,
    val batteryTempC: Double?,
    val cpuTempC: Double?,
    val gpuTempC: Double?,
    val socTempC: Double?,
    val maxZoneTempC: Double?,
    val maxRecordedTempC: Double?,
    val osThermalStatusCode: Int?,
    val osThermalStatusLabel: String,
    val osThermalHeadroom: Float?,
    val overallSeverity: HealthSeverity
)

data class CpuFrequencyResidency(
    val freqMhz: Double,
    val timeMs: Long,
    val percent: Double
)

data class CpuTimeInStateCluster(
    val clusterName: String,
    val sourcePath: String,
    val entries: List<CpuFrequencyResidency>
)

data class CpuCoreTelemetry(
    val coreIndex: Int,
    val isOnline: Boolean?,
    val currentFreqMhz: Double?,
    val minFreqMhz: Double?,
    val maxFreqMhz: Double?,
    val hardwareMaxFreqMhz: Double?,
    val governor: String?,
    val utilizationPercent: Double?,
    val source: DataSourceOrigin
)

data class CpuTelemetry(
    val modelName: String,
    val hardwarePlatform: String,
    val architecture: String,
    val totalCores: Int,
    val onlineCores: Int,
    val totalUtilizationPercent: Double?,
    val loadAvg1m: Double?,
    val loadAvg5m: Double?,
    val loadAvg15m: Double?,
    val cores: List<CpuCoreTelemetry>,
    val availableGovernors: List<String>,
    val availableFrequenciesMhz: List<Double>,
    val source: DataSourceOrigin,
    val totalElapsedMs: Long = 0L,
    val awakeUptimeMs: Long = 0L,
    val deepSleepMs: Long = 0L,
    val deepSleepPercent: Double = 0.0,
    val timeInStateClusters: List<CpuTimeInStateCluster> = emptyList()
)

data class GpuTelemetry(
    val gpuName: String,
    val vendor: String?,
    val glEsVersion: String?,
    val currentFreqMhz: Double?,
    val maxFreqMhz: Double?,
    val minFreqMhz: Double?,
    val utilizationPercent: Double?,
    val governor: String?,
    val temperatureC: Double?,
    val availableFrequenciesMhz: List<Double>,
    val sysfsNodePath: String?,
    val source: DataSourceOrigin,
    val isSysfsAvailable: Boolean
)

data class MemoryTelemetry(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val usedRamBytes: Long,
    val freeRamBytes: Long?,
    val cachedRamBytes: Long?,
    val buffersBytes: Long?,
    val swapTotalBytes: Long?,
    val swapFreeBytes: Long?,
    val utilizationPercent: Double,
    val isLowMemory: Boolean,
    val thresholdBytes: Long,
    val interpretation: String,
    val sources: List<DataSourceOrigin>,
    val dirtyBytes: Long? = null,
    val slabBytes: Long? = null,
    val pageTablesBytes: Long? = null,
    val zramOrigDataBytes: Long? = null,
    val zramComprDataBytes: Long? = null,
    val zramMemUsedBytes: Long? = null,
    val zramCompressionRatio: Double? = null
)

data class MountPointInfo(
    val device: String,
    val mountPoint: String,
    val fsType: String,
    val options: String
)

data class BlockDeviceInfo(
    val name: String,
    val sizeBytes: Long?,
    val rotational: Boolean?,
    val scheduler: String?,
    val source: DataSourceOrigin
)

data class PartitionInfo(
    val major: Int,
    val minor: Int,
    val blocksKb: Long,
    val name: String
)

data class EmmcHardwareHealth(
    val deviceName: String?,
    val manfId: String?,
    val oemId: String?,
    val hwRev: String?,
    val fwRev: String?,
    val lifeTimeTypeA: String?,
    val lifeTimeTypeB: String?,
    val preEolStatus: String?,
    val preEolSeverity: HealthSeverity,
    val source: DataSourceOrigin
)

data class StorageBenchmarkResult(
    val timestampMs: Long,
    val payloadMb: Int,
    val writeSpeedMbPerSec: Double,
    val readSpeedMbPerSec: Double,
    val durationMs: Long
)

data class StorageTelemetry(
    val internalTotalBytes: Long,
    val internalUsedBytes: Long,
    val internalFreeBytes: Long,
    val usagePercent: Double,
    val primaryFsType: String?,
    val mounts: List<MountPointInfo>,
    val blockDevices: List<BlockDeviceInfo>,
    val diagnosticNote: String,
    val lastBenchmark: StorageBenchmarkResult? = null,
    val partitions: List<PartitionInfo> = emptyList(),
    val emmcHealth: EmmcHardwareHealth? = null
)

data class HardwareSensorMetadata(
    val name: String,
    val type: Int,
    val typeLabel: String,
    val vendor: String,
    val version: Int,
    val resolution: Float,
    val maxRange: Float,
    val powerMa: Float,
    val minDelayUs: Int,
    val isAvailable: Boolean
)

data class LiveSensorSample(
    val sensorType: Int,
    val typeLabel: String,
    val sensorName: String,
    val values: List<Float>,
    val unit: String,
    val accuracy: Int,
    val timestampNs: Long
)

data class KernelTunableItem(
    val label: String,
    val path: String,
    val value: String?,
    val source: DataSourceOrigin
)

data class DisplayTelemetry(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val supportedRefreshRatesHz: List<Float>,
    val isHdrCapable: Boolean
)

data class KernelSystemInfo(
    val deviceModel: String,
    val manufacturer: String,
    val deviceCodename: String,
    val board: String,
    val hardware: String,
    val androidVersion: String,
    val sdkInt: Int,
    val securityPatch: String,
    val buildFingerprint: String,
    val supportedAbis: List<String>,
    val kernelVersion: String,
    val linuxProcVersion: String?,
    val selinuxStatus: String,
    val rootStatus: RootStatus,
    val rootProbeDetail: String,
    val magiskVersion: String?,
    val availableGovernors: List<String>,
    val availableCpuFrequenciesMhz: List<Double>,
    val thermalConfigPreview: String?,
    val tunables: List<KernelTunableItem> = emptyList(),
    val displayInfo: DisplayTelemetry? = null
)

data class DiagnosticCheckResult(
    val id: String,
    val category: String,
    val status: DiagnosticStatus,
    val summary: String,
    val technicalReason: String
)

data class DiagnosticReport(
    val generatedAtMs: Long,
    val deviceLabel: String,
    val checks: List<DiagnosticCheckResult>,
    val passCount: Int,
    val warningCount: Int,
    val unavailableCount: Int,
    val errorCount: Int
)

data class TelemetryHistoryPoint(
    val timestampMs: Long,
    val value: Double
)

data class MetricMinMax(
    val min: Double? = null,
    val max: Double? = null
) {
    fun update(sample: Double?): MetricMinMax {
        if (sample == null || !sample.isFinite()) return this
        val nextMin = if (min == null) sample else kotlin.math.min(min, sample)
        val nextMax = if (max == null) sample else kotlin.math.max(max, sample)
        return MetricMinMax(nextMin, nextMax)
    }
}

data class TelemetryHistoryState(
    val batteryCurrentMa: List<TelemetryHistoryPoint> = emptyList(),
    val batteryVoltageMv: List<TelemetryHistoryPoint> = emptyList(),
    val batteryTempC: List<TelemetryHistoryPoint> = emptyList(),
    val batteryPercent: List<TelemetryHistoryPoint> = emptyList(),
    val chargingPowerWatts: List<TelemetryHistoryPoint> = emptyList(),
    val usbPowerWatts: List<TelemetryHistoryPoint> = emptyList(),
    val cpuUtilization: List<TelemetryHistoryPoint> = emptyList(),
    val gpuUtilization: List<TelemetryHistoryPoint> = emptyList(),
    val ramUtilization: List<TelemetryHistoryPoint> = emptyList(),
    val maxThermalTempC: List<TelemetryHistoryPoint> = emptyList(),
    val batteryTempMinMax: MetricMinMax = MetricMinMax(),
    val batteryCurrentMinMax: MetricMinMax = MetricMinMax(),
    val batteryVoltageMinMax: MetricMinMax = MetricMinMax(),
    val thermalPeakMinMax: MetricMinMax = MetricMinMax(),
    val cpuLoadMinMax: MetricMinMax = MetricMinMax(),
    val ramUsageMinMax: MetricMinMax = MetricMinMax()
)

data class HardwareSnapshot(
    val timestampMs: Long,
    val battery: BatteryTelemetry,
    val charging: ChargingTelemetry,
    val thermal: ThermalTelemetry,
    val cpu: CpuTelemetry,
    val gpu: GpuTelemetry,
    val memory: MemoryTelemetry,
    val storage: StorageTelemetry,
    val sensors: List<HardwareSensorMetadata>,
    val kernelSystem: KernelSystemInfo
)
