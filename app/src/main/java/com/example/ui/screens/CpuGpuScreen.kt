package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.CpuTelemetry
import com.example.domain.GpuTelemetry
import com.example.domain.TelemetryHistoryState
import com.example.ui.components.HorizontalBarMetricRow
import com.example.ui.components.LabCard
import com.example.ui.components.MetricTile
import com.example.ui.components.RealTimeLineChart
import com.example.ui.components.SectionHeader
import com.example.ui.components.SourceOriginTag
import com.example.ui.components.StatusPill
import com.example.ui.components.TelemetryRow
import com.example.ui.components.UsageProgressBar
import com.example.ui.theme.LabAmber
import com.example.ui.theme.LabBlue
import com.example.ui.theme.LabBorderSubtle
import com.example.ui.theme.LabCyan
import com.example.ui.theme.LabGreen
import com.example.ui.theme.LabRed
import com.example.ui.theme.LabTextMuted
import com.example.ui.theme.LabTextSecondary
import com.example.ui.theme.MonoValueSmall
import com.example.utils.FormatUtils

@Composable
fun CpuGpuScreen(
    cpu: CpuTelemetry,
    gpu: GpuTelemetry,
    history: TelemetryHistoryState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("cpu_gpu_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LabCard {
                SectionHeader(
                    title = cpu.modelName,
                    subtitle = "CPU SUBSYSTEM • ${cpu.architecture}",
                    trailing = {
                        StatusPill(
                            label = "${cpu.onlineCores}/${cpu.totalCores} ONLINE",
                            color = LabGreen
                        )
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                TelemetryRow(label = "Hardware Platform", value = cpu.hardwarePlatform, source = cpu.source)
                TelemetryRow(
                    label = "Total CPU Utilization",
                    value = FormatUtils.formatDecimal(cpu.totalUtilizationPercent, 1, "%"),
                    valueColor = LabCyan
                )
                val loadText = if (cpu.loadAvg1m != null) {
                    "${FormatUtils.formatDecimal(cpu.loadAvg1m, 2)} / ${FormatUtils.formatDecimal(cpu.loadAvg5m, 2)} / ${FormatUtils.formatDecimal(cpu.loadAvg15m, 2)}"
                } else "N/A"
                TelemetryRow(label = "Load Avg (1m / 5m / 15m)", value = loadText)
            }
        }

        item {
            RealTimeLineChart(
                title = "CPU Utilization (%)",
                unit = "%",
                points = history.cpuUtilization,
                lineColor = LabCyan,
                minMax = history.cpuLoadMinMax,
                decimals = 1
            )
        }

        if (cpu.totalElapsedMs > 0L) {
            item {
                val awakePct = (100.0 - cpu.deepSleepPercent).coerceIn(0.0, 100.0)
                LabCard {
                    SectionHeader(
                        title = "SoC Sleep & Uptime Efficiency",
                        subtitle = "SYSTEMCLOCK ELAPSED REALTIME VS UPTIME"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalBarMetricRow(
                        label = "Deep Sleep (${FormatUtils.formatDurationMs(cpu.deepSleepMs)})",
                        valueLabel = FormatUtils.formatDecimal(cpu.deepSleepPercent, 1, "%"),
                        fraction = (cpu.deepSleepPercent / 100.0).toFloat(),
                        color = LabGreen
                    )
                    HorizontalBarMetricRow(
                        label = "Awake Time (${FormatUtils.formatDurationMs(cpu.awakeUptimeMs)})",
                        valueLabel = FormatUtils.formatDecimal(awakePct, 1, "%"),
                        fraction = (awakePct / 100.0).toFloat(),
                        color = LabAmber
                    )
                    TelemetryRow(
                        label = "Total Time Since Boot",
                        value = FormatUtils.formatDurationMs(cpu.totalElapsedMs)
                    )
                }
            }
        }

        if (cpu.timeInStateClusters.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "CPU Frequency Residency (time_in_state)",
                    subtitle = "KERNEL CPUFREQ STATS"
                )
            }
            items(cpu.timeInStateClusters, key = { it.sourcePath }) { cluster ->
                LabCard {
                    Text(
                        text = cluster.clusterName,
                        style = MaterialTheme.typography.titleSmall,
                        color = LabCyan
                    )
                    Text(
                        text = cluster.sourcePath,
                        style = MonoValueSmall,
                        color = LabTextMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    cluster.entries.sortedByDescending { it.percent }.take(8).forEach { entry ->
                        HorizontalBarMetricRow(
                            label = "${FormatUtils.formatFreqMhz(entry.freqMhz)} (${FormatUtils.formatDurationMs(entry.timeMs)})",
                            valueLabel = FormatUtils.formatDecimal(entry.percent, 1, "%"),
                            fraction = (entry.percent / 100.0).toFloat(),
                            color = if (entry.percent >= 25.0) LabCyan else LabBlue
                        )
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "Per-Core Frequency & Utilization",
                subtitle = "/sys/devices/system/cpu/cpu*/cpufreq/"
            )
        }

        items(cpu.cores, key = { it.coreIndex }) { core ->
            LabCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CPU Core #${core.coreIndex}",
                            style = MaterialTheme.typography.titleSmall,
                            color = LabCyan
                        )
                        Text(
                            text = "Governor: ${core.governor ?: "N/A"} | Range: ${FormatUtils.formatFreqMhz(core.minFreqMhz)} - ${FormatUtils.formatFreqMhz(core.maxFreqMhz ?: core.hardwareMaxFreqMhz)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = LabTextSecondary
                        )
                    }
                    StatusPill(
                        label = when (core.isOnline) {
                            true -> FormatUtils.formatFreqMhz(core.currentFreqMhz)
                            false -> "OFFLINE"
                            null -> FormatUtils.formatFreqMhz(core.currentFreqMhz)
                        },
                        color = when {
                            core.isOnline == false -> LabTextMuted
                            core.currentFreqMhz != null -> LabGreen
                            else -> LabAmber
                        }
                    )
                }

                val maxFreq = core.maxFreqMhz ?: core.hardwareMaxFreqMhz
                if (core.currentFreqMhz != null && maxFreq != null && maxFreq > 0.0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val ratio = (core.currentFreqMhz / maxFreq).toFloat()
                    UsageProgressBar(
                        progress = ratio,
                        color = if (ratio > 0.85f) LabAmber else LabCyan
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Core Load: ${FormatUtils.formatDecimal(core.utilizationPercent, 1, "%")}",
                        style = MonoValueSmall,
                        color = LabTextSecondary
                    )
                    SourceOriginTag(source = core.source)
                }
            }
        }

        item {
            SectionHeader(
                title = "GPU Telemetry (Adreno / Devfreq)",
                subtitle = "KGSL / DEVFREQ / OPENGL ES"
            )
        }

        item {
            LabCard(
                borderColor = if (gpu.isSysfsAvailable) LabBlue.copy(alpha = 0.5f) else LabBorderSubtle
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(gpu.gpuName, style = MaterialTheme.typography.titleMedium, color = LabBlue)
                        Text(
                            text = "Vendor: ${gpu.vendor ?: "N/A"} • OpenGL ES ${gpu.glEsVersion ?: "N/A"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = LabTextSecondary
                        )
                    }
                    StatusPill(
                        label = if (gpu.isSysfsAvailable) "SYSFS ACTIVE" else "DRIVER ONLY",
                        color = if (gpu.isSysfsAvailable) LabGreen else LabAmber
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricTile(
                        label = "GPU Clock",
                        value = FormatUtils.formatFreqMhz(gpu.currentFreqMhz),
                        accentColor = LabBlue,
                        subtext = "Max: ${FormatUtils.formatFreqMhz(gpu.maxFreqMhz)}",
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "GPU Busy",
                        value = FormatUtils.formatDecimal(gpu.utilizationPercent, 1, "%"),
                        accentColor = when {
                            gpu.utilizationPercent == null -> LabTextMuted
                            gpu.utilizationPercent >= 85.0 -> LabRed
                            else -> LabCyan
                        },
                        subtext = "Gov: ${gpu.governor ?: "N/A"}",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TelemetryRow(
                    label = "GPU Temperature",
                    value = FormatUtils.formatTemp(gpu.temperatureC)
                )
                if (gpu.availableFrequenciesMhz.isNotEmpty()) {
                    TelemetryRow(
                        label = "GPU Frequency Steps",
                        value = gpu.availableFrequenciesMhz.joinToString(", ") { "${it.toInt()} MHz" }
                    )
                }
                SourceOriginTag(source = gpu.source, nodePath = gpu.sysfsNodePath)
            }
        }

        if (history.gpuUtilization.isNotEmpty()) {
            item {
                RealTimeLineChart(
                    title = "GPU Busy Utilization (%)",
                    unit = "%",
                    points = history.gpuUtilization,
                    lineColor = LabBlue,
                    decimals = 1
                )
            }
        }
    }
}
