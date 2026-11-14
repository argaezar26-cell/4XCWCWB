package com.example.data

import android.content.Context
import com.example.domain.HardwareSnapshot
import com.example.domain.LiveSensorSample
import com.example.domain.StorageBenchmarkResult
import com.example.root.RootProbeResult
import com.example.root.RootShellManager
import com.example.sensors.DeviceSensorManager
import com.example.utils.StorageBenchmark
import kotlinx.coroutines.flow.Flow

interface HardwareRepository {
    suspend fun collectSnapshot(forceRootProbe: Boolean = false): HardwareSnapshot
    suspend fun reProbeRoot(): RootProbeResult
    suspend fun runStorageBenchmark(payloadMb: Int = 4): StorageBenchmarkResult
    fun observeLiveSensors(): Flow<Map<Int, LiveSensorSample>>
    fun resetThermalPeak()
}

class HardwareRepositoryImpl(
    private val context: Context,
    private val rootShellManager: RootShellManager = RootShellManager(),
    private val sysfsReader: SysfsReader = SysfsReader(rootShellManager),
    private val batteryAndChargingDataSource: BatteryAndChargingDataSource = BatteryAndChargingDataSource(context, sysfsReader),
    private val thermalDataSource: ThermalDataSource = ThermalDataSource(context, sysfsReader),
    private val cpuGpuDataSource: CpuGpuDataSource = CpuGpuDataSource(context, sysfsReader),
    private val memoryStorageDataSource: MemoryStorageDataSource = MemoryStorageDataSource(context, sysfsReader),
    private val kernelSystemDataSource: KernelSystemDataSource = KernelSystemDataSource(sysfsReader, rootShellManager),
    private val deviceSensorManager: DeviceSensorManager = DeviceSensorManager(context)
) : HardwareRepository {

    @Volatile
    private var lastBenchmarkResult: StorageBenchmarkResult? = null

    override suspend fun collectSnapshot(forceRootProbe: Boolean): HardwareSnapshot {
        val (battery, charging) = batteryAndChargingDataSource.readTelemetry()
        val thermal = thermalDataSource.readTelemetry(battery.temperatureC.value)
        val cpu = cpuGpuDataSource.readCpuTelemetry()
        val gpu = cpuGpuDataSource.readGpuTelemetry()
        val memory = memoryStorageDataSource.readMemoryTelemetry()
        val storage = memoryStorageDataSource.readStorageTelemetry(lastBenchmarkResult)
        val sensors = deviceSensorManager.getSensorInventory()
        val kernelSystem = kernelSystemDataSource.readKernelSystemInfo(
            availableGovernors = cpu.availableGovernors,
            availableFrequenciesMhz = cpu.availableFrequenciesMhz,
            forceRootProbe = forceRootProbe
        )

        return HardwareSnapshot(
            timestampMs = System.currentTimeMillis(),
            battery = battery,
            charging = charging,
            thermal = thermal,
            cpu = cpu,
            gpu = gpu,
            memory = memory,
            storage = storage,
            sensors = sensors,
            kernelSystem = kernelSystem
        )
    }

    override suspend fun reProbeRoot(): RootProbeResult {
        return rootShellManager.probeRootAccess(forceRefresh = true)
    }

    override suspend fun runStorageBenchmark(payloadMb: Int): StorageBenchmarkResult {
        val result = StorageBenchmark.runBoundedIoTest(context, payloadMb)
        lastBenchmarkResult = result
        return result
    }

    override fun observeLiveSensors(): Flow<Map<Int, LiveSensorSample>> {
        return deviceSensorManager.observeLiveSensors()
    }

    override fun resetThermalPeak() {
        thermalDataSource.resetPeakRecord()
    }
}
