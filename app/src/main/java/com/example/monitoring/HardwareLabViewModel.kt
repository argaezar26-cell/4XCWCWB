package com.example.monitoring

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.HardwareRepository
import com.example.data.HardwareRepositoryImpl
import com.example.diagnostics.DiagnosticEngine
import com.example.domain.DiagnosticReport
import com.example.domain.HardwareSnapshot
import com.example.domain.LiveSensorSample
import com.example.domain.RefreshInterval
import com.example.domain.TelemetryHistoryPoint
import com.example.domain.TelemetryHistoryState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class LabPage(val route: String, val title: String, val shortLabel: String) {
    OVERVIEW("overview", "Overview", "Overview"),
    BATTERY("battery", "Battery Lab", "Battery"),
    CHARGING("charging", "Charging Lab", "Charging"),
    THERMAL("thermal", "Thermal Lab", "Thermal"),
    CPU_GPU("cpu_gpu", "CPU & GPU Lab", "CPU/GPU"),
    MEMORY_STORAGE("memory_storage", "Memory & Storage", "Mem/Disk"),
    SENSORS("sensors", "Sensor Lab", "Sensors"),
    KERNEL_ROOT("kernel_root", "Kernel & Root", "Kernel"),
    DIAGNOSTICS("diagnostics", "Diagnostic Center", "Diag")
}

data class HardwareLabUiState(
    val isLoadingInitial: Boolean = true,
    val currentPage: LabPage = LabPage.OVERVIEW,
    val snapshot: HardwareSnapshot? = null,
    val history: TelemetryHistoryState = TelemetryHistoryState(),
    val refreshInterval: RefreshInterval = RefreshInterval.TWO_SEC,
    val isPaused: Boolean = false,
    val isAppInForeground: Boolean = true,
    val backgroundMonitoringEnabled: Boolean = false,
    val isProbingRoot: Boolean = false,
    val isRunningBenchmark: Boolean = false,
    val benchmarkMessage: String? = null,
    val isSensorLiveStreamActive: Boolean = false,
    val liveSensorSamples: Map<Int, LiveSensorSample> = emptyMap(),
    val diagnosticReport: DiagnosticReport? = null,
    val lastError: String? = null
)

class HardwareLabViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository: HardwareRepository = HardwareRepositoryImpl(application.applicationContext)
    private val snapshotMutex = kotlinx.coroutines.sync.Mutex()

    private val _uiState = MutableStateFlow(HardwareLabUiState())
    val uiState: StateFlow<HardwareLabUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var sensorStreamJob: Job? = null
    private val maxHistoryPoints = 60

    init {
        viewModelScope.launch {
            collectAndPublishSnapshot(allowRootProbe = false, forceRootProbe = false)
            startOrRestartPolling()
        }
        viewModelScope.launch {
            delay(300L)
            _uiState.update { it.copy(isProbingRoot = true) }
            try {
                repository.reProbeRoot()
                collectAndPublishSnapshot(allowRootProbe = true, forceRootProbe = false)
            } finally {
                _uiState.update { it.copy(isProbingRoot = false) }
            }
        }
    }

    fun selectPage(page: LabPage) {
        _uiState.update { state ->
            state.copy(currentPage = page)
        }
        if (page != LabPage.SENSORS && _uiState.value.isSensorLiveStreamActive) {
            setSensorLiveStream(false)
        }
    }

    fun setRefreshInterval(interval: RefreshInterval) {
        _uiState.update { it.copy(refreshInterval = interval) }
        startOrRestartPolling()
    }

    fun togglePause() {
        val nextPaused = !_uiState.value.isPaused
        _uiState.update { it.copy(isPaused = nextPaused) }
        if (nextPaused) {
            pollingJob?.cancel()
            pollingJob = null
        } else {
            startOrRestartPolling()
        }
    }

    fun onForegroundChanged(inForeground: Boolean) {
        _uiState.update { it.copy(isAppInForeground = inForeground) }
        if (!inForeground) {
            if (_uiState.value.isSensorLiveStreamActive) {
                setSensorLiveStream(false)
            }
            if (!_uiState.value.backgroundMonitoringEnabled) {
                pollingJob?.cancel()
                pollingJob = null
            }
        } else if (!_uiState.value.isPaused) {
            startOrRestartPolling()
        }
    }

    fun setBackgroundMonitoring(enabled: Boolean) {
        _uiState.update { it.copy(backgroundMonitoringEnabled = enabled) }
        val app = getApplication<Application>()
        if (enabled) {
            HardwareMonitorService.start(app)
            if (!_uiState.value.isPaused) {
                startOrRestartPolling()
            }
        } else {
            HardwareMonitorService.stop(app)
            if (!_uiState.value.isAppInForeground) {
                pollingJob?.cancel()
                pollingJob = null
            }
        }
    }

    fun resetStatistics() {
        repository.resetThermalPeak()
        _uiState.update { it.copy(history = TelemetryHistoryState()) }
        refreshNow()
    }

    fun refreshNow() {
        viewModelScope.launch {
            collectAndPublishSnapshot(allowRootProbe = false, forceRootProbe = false)
        }
    }

    fun reProbeRoot() {
        viewModelScope.launch {
            _uiState.update { it.copy(isProbingRoot = true) }
            try {
                repository.reProbeRoot()
                collectAndPublishSnapshot(allowRootProbe = true, forceRootProbe = false)
            } finally {
                _uiState.update { it.copy(isProbingRoot = false) }
            }
        }
    }

    fun runStorageBenchmark() {
        if (_uiState.value.isRunningBenchmark) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRunningBenchmark = true, benchmarkMessage = null) }
            try {
                val res = repository.runStorageBenchmark(payloadMb = 4)
                _uiState.update {
                    it.copy(
                        isRunningBenchmark = false,
                        benchmarkMessage = "4 MB cache-dir test completed in ${res.durationMs} ms"
                    )
                }
                collectAndPublishSnapshot(allowRootProbe = false, forceRootProbe = false)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRunningBenchmark = false,
                        benchmarkMessage = "Benchmark failed: ${e.message ?: "I/O error"}"
                    )
                }
            }
        }
    }

    fun setSensorLiveStream(active: Boolean) {
        sensorStreamJob?.cancel()
        sensorStreamJob = null
        _uiState.update { it.copy(isSensorLiveStreamActive = active) }
        if (active) {
            sensorStreamJob = viewModelScope.launch {
                repository.observeLiveSensors().collect { samples ->
                    _uiState.update { it.copy(liveSensorSamples = samples) }
                }
            }
        }
    }

    fun runDiagnostics(): DiagnosticReport? {
        val snapshot = _uiState.value.snapshot ?: return null
        val report = DiagnosticEngine.runDiagnostics(snapshot)
        _uiState.update { it.copy(diagnosticReport = report) }
        return report
    }

    fun buildTextReport(): String? {
        val snapshot = _uiState.value.snapshot ?: return null
        val report = _uiState.value.diagnosticReport ?: DiagnosticEngine.runDiagnostics(snapshot).also { rep ->
            _uiState.update { it.copy(diagnosticReport = rep) }
        }
        return DiagnosticEngine.formatAsText(report, snapshot)
    }

    fun buildCsvReport(): String? {
        val snapshot = _uiState.value.snapshot ?: return null
        val report = _uiState.value.diagnosticReport ?: DiagnosticEngine.runDiagnostics(snapshot).also { rep ->
            _uiState.update { it.copy(diagnosticReport = rep) }
        }
        return DiagnosticEngine.formatAsCsv(report, snapshot)
    }

    private fun startOrRestartPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val intervalMs = _uiState.value.refreshInterval.seconds * 1000L
                delay(intervalMs)
                val state = _uiState.value
                val shouldPoll = !state.isPaused && (state.isAppInForeground || state.backgroundMonitoringEnabled)
                if (!shouldPoll) break

                collectAndPublishSnapshot(allowRootProbe = false, forceRootProbe = false)
            }
        }
    }

    private suspend fun collectAndPublishSnapshot(
        allowRootProbe: Boolean,
        forceRootProbe: Boolean
    ) {
        snapshotMutex.lock()
        try {
            val snapshot = repository.collectSnapshot(
                allowRootProbe = allowRootProbe,
                forceRootProbe = forceRootProbe
            )
            val ts = snapshot.timestampMs
            _uiState.update { prev ->
                val h = prev.history
                val nextHistory = h.copy(
                    batteryCurrentMa = appendPoint(h.batteryCurrentMa, ts, snapshot.battery.currentNowMa.value),
                    batteryVoltageMv = appendPoint(h.batteryVoltageMv, ts, snapshot.battery.voltageMv.value),
                    batteryTempC = appendPoint(h.batteryTempC, ts, snapshot.battery.temperatureC.value),
                    batteryPercent = appendPoint(h.batteryPercent, ts, snapshot.battery.percentage.value?.toDouble()),
                    chargingPowerWatts = appendPoint(
                        h.chargingPowerWatts,
                        ts,
                        snapshot.charging.batteryPowerWatts.value?.let { abs(it) }
                    ),
                    usbPowerWatts = appendPoint(h.usbPowerWatts, ts, snapshot.charging.usbPowerWatts.value),
                    cpuUtilization = appendPoint(h.cpuUtilization, ts, snapshot.cpu.totalUtilizationPercent),
                    gpuUtilization = appendPoint(h.gpuUtilization, ts, snapshot.gpu.utilizationPercent),
                    ramUtilization = appendPoint(h.ramUtilization, ts, snapshot.memory.utilizationPercent),
                    maxThermalTempC = appendPoint(h.maxThermalTempC, ts, snapshot.thermal.maxZoneTempC),
                    batteryTempMinMax = h.batteryTempMinMax.update(snapshot.battery.temperatureC.value),
                    batteryCurrentMinMax = h.batteryCurrentMinMax.update(snapshot.battery.currentNowMa.value),
                    batteryVoltageMinMax = h.batteryVoltageMinMax.update(snapshot.battery.voltageMv.value),
                    thermalPeakMinMax = h.thermalPeakMinMax.update(snapshot.thermal.maxZoneTempC),
                    cpuLoadMinMax = h.cpuLoadMinMax.update(snapshot.cpu.totalUtilizationPercent),
                    ramUsageMinMax = h.ramUsageMinMax.update(snapshot.memory.utilizationPercent)
                )
                val nextReport = if (prev.currentPage == LabPage.DIAGNOSTICS || prev.diagnosticReport != null) {
                    DiagnosticEngine.runDiagnostics(snapshot)
                } else {
                    prev.diagnosticReport
                }
                prev.copy(
                    isLoadingInitial = false,
                    snapshot = snapshot,
                    history = nextHistory,
                    diagnosticReport = nextReport,
                    lastError = null
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoadingInitial = false,
                    lastError = e.message ?: "Telemetry read error"
                )
            }
        } finally {
            snapshotMutex.unlock()
        }
    }

    private fun appendPoint(
        existing: List<TelemetryHistoryPoint>,
        timestampMs: Long,
        value: Double?
    ): List<TelemetryHistoryPoint> {
        if (value == null || !value.isFinite()) return existing
        val updated = existing + TelemetryHistoryPoint(timestampMs, value)
        return if (updated.size > maxHistoryPoints) {
            updated.takeLast(maxHistoryPoints)
        } else {
            updated
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        sensorStreamJob?.cancel()
        if (_uiState.value.backgroundMonitoringEnabled) {
            HardwareMonitorService.stop(getApplication())
        }
        super.onCleared()
    }
}
