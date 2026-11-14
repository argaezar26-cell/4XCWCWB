package com.example.data

import android.content.Context
import com.example.domain.HardwareSnapshot
import com.example.domain.LiveSensorSample
import com.example.domain.StorageBenchmarkResult
import com.example.root.RootProbeResult
import com.example.root.RootShellManager
import com.example.sensors.DeviceSensorManager
import com.example.utils.StorageBenchmark
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

interface HardwareRepository {
    suspend fun collectSnapshot(allowRootProbe: Boolean = false, forceRootProbe: Boolean = false): HardwareSnapshot
    suspend fun reProbeRoot(): RootProbeResult
    suspend fun runStorageBenchmark(payloadMb: Int = 4): StorageBenchmarkResult
    fun observeLiveSensors(): kotlinx.coroutines.flow.Flow<Map<Int, LiveSensorSample>>
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
    private val kernelSystemDataSource: KernelSystemDataSource = KernelSystemDataSource(context, sysfsReader, rootShellManager),
    private val deviceSensorManager: DeviceSensorManager = DeviceSensorManager(context)
) : HardwareRepository {

    @Volatile
    private var lastBenchmarkResult: StorageBenchmarkResult? = null

    override suspend fun collectSnapshot(
        allowRootProbe: Boolean,
        forceRootProbe: Boolean
    ): HardwareSnapshot = coroutineScope {
        val batteryDeferred = async {
            batteryAndChargingDataSource.readTelemetry()
        }
        val cpuDeferred = async {
            cpuGpuDataSource.readCpuTelemetry()
        }
        val gpuDeferred = async {
            withTimeoutOrNull(1500L) {
                cpuGpuDataSource.readGpuTelemetry()
            } ?: cpuGpuDataSource.fallbackGpuTelemetry()
        }
        val memoryDeferred = async {
            memoryStorageDataSource.readMemoryTelemetry()
        }
        val storageDeferred = async {
            memoryStorageDataSource.readStorageTelemetry(lastBenchmarkResult)
        }
        val sensorsDeferred = async {
            deviceSensorManager.getSensorInventory()
        }

        val (battery, charging) = batteryDeferred.await()
        val thermalDeferred = async {
            thermalDataSource.readTelemetry(battery.temperatureC.value)
        }

        val cpu = cpuDeferred.await()
        val gpu = gpuDeferred.await()
        val memory = memoryDeferred.await()
        val storage = storageDeferred.await()
        val sensors = sensorsDeferred.await()
        val thermal = thermalDeferred.await()

        val kernelSystem = kernelSystemDataSource.readKernelSystemInfo(
            availableGovernors = cpu.availableGovernors,
            availableFrequenciesMhz = cpu.availableFrequenciesMhz,
            allowRootProbe = allowRootProbe,
            forceRootProbe = forceRootProbe
        )

        HardwareSnapshot(
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

    override fun observeLiveSensors(): kotlinx.coroutines.flow.Flow<Map<Int, LiveSensorSample>> {
        return deviceSensorManager.observeLiveSensors()
    }

    override fun resetThermalPeak() {
        thermalDataSource.resetPeakRecord()
    }
}
