package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.MemoryTelemetry
import com.example.domain.StorageTelemetry
import com.example.domain.TelemetryHistoryState
import com.example.ui.components.LabCard
import com.example.ui.components.MetricTile
import com.example.ui.components.RealTimeLineChart
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.TelemetryRow
import com.example.ui.components.UsageProgressBar
import com.example.ui.theme.LabAmber
import com.example.ui.theme.LabBackground
import com.example.ui.theme.LabCyan
import com.example.ui.theme.LabGreen
import com.example.ui.theme.LabRed
import com.example.ui.theme.LabTextMuted
import com.example.ui.theme.LabTextSecondary
import com.example.utils.FormatUtils

@Composable
fun MemoryStorageScreen(
    memory: MemoryTelemetry,
    storage: StorageTelemetry,
    history: TelemetryHistoryState,
    isRunningBenchmark: Boolean,
    benchmarkMessage: String?,
    onRunBenchmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("memory_storage_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val ramColor = when {
                memory.isLowMemory || memory.utilizationPercent >= 90.0 -> LabRed
                memory.utilizationPercent >= 78.0 -> LabAmber
                else -> LabGreen
            }
            LabCard {
                SectionHeader(
                    title = "RAM Telemetry",
                    subtitle = memory.sources.joinToString(" + ") { it.label },
                    trailing = {
                        StatusPill(
                            label = if (memory.isLowMemory) "LOW MEMORY" else "${FormatUtils.formatDecimal(memory.utilizationPercent, 1)}%",
                            color = ramColor
                        )
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                UsageProgressBar(
                    progress = (memory.utilizationPercent / 100.0).toFloat(),
                    color = ramColor
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricTile(
                        label = "Total RAM",
                        value = FormatUtils.formatBytes(memory.totalRamBytes),
                        accentColor = LabCyan,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "Available RAM",
                        value = FormatUtils.formatBytes(memory.availableRamBytes),
                        accentColor = LabGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TelemetryRow(label = "Used RAM", value = FormatUtils.formatBytes(memory.usedRamBytes))
                TelemetryRow(label = "Cached RAM (Reclaimable)", value = FormatUtils.formatBytes(memory.cachedRamBytes))
                TelemetryRow(label = "Buffers", value = FormatUtils.formatBytes(memory.buffersBytes))
                TelemetryRow(label = "Free RAM (Unallocated)", value = FormatUtils.formatBytes(memory.freeRamBytes))
                val swapText = if (memory.swapTotalBytes != null && memory.swapTotalBytes > 0L) {
                    val swapUsed = (memory.swapTotalBytes - (memory.swapFreeBytes ?: 0L)).coerceAtLeast(0L)
                    "${FormatUtils.formatBytes(swapUsed)} / ${FormatUtils.formatBytes(memory.swapTotalBytes)}"
                } else "Unavailable / Disabled"
                TelemetryRow(label = "zRAM / Swap Usage", value = swapText)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = memory.interpretation,
                    style = MaterialTheme.typography.bodySmall,
                    color = LabCyan
                )
            }
        }

        item {
            RealTimeLineChart(
                title = "RAM Utilization (%)",
                unit = "%",
                points = history.ramUtilization,
                lineColor = LabGreen,
                minMax = history.ramUsageMinMax,
                decimals = 1
            )
        }

        item {
            val stColor = when {
                storage.usagePercent >= 92.0 -> LabRed
                storage.usagePercent >= 80.0 -> LabAmber
                else -> LabCyan
            }
            LabCard {
                SectionHeader(
                    title = "Internal Storage (/data)",
                    subtitle = "STATFS • /proc/mounts • /sys/block/",
                    trailing = {
                        StatusPill(
                            label = storage.primaryFsType?.uppercase() ?: "UNKNOWN FS",
                            color = stColor
                        )
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                UsageProgressBar(
                    progress = (storage.usagePercent / 100.0).toFloat(),
                    color = stColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TelemetryRow(label = "Total Capacity", value = FormatUtils.formatBytes(storage.internalTotalBytes))
                TelemetryRow(label = "Used Space", value = FormatUtils.formatBytes(storage.internalUsedBytes))
                TelemetryRow(label = "Free Space", value = FormatUtils.formatBytes(storage.internalFreeBytes))
                TelemetryRow(label = "Usage Percentage", value = FormatUtils.formatDecimal(storage.usagePercent, 1, "%"))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = storage.diagnosticNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = LabAmber
                )
            }
        }

        item {
            LabCard {
                SectionHeader(
                    title = "Non-Destructive I/O Benchmark",
                    subtitle = "OPTIONAL 4 MB CACHE TEST"
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Writes and reads a temporary 4 MB payload inside app cacheDir with fsync, then deletes it immediately. Safe for eMMC/UFS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabTextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onRunBenchmark,
                    enabled = !isRunningBenchmark,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LabCyan,
                        contentColor = LabBackground
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("run_storage_benchmark_button")
                ) {
                    if (isRunningBenchmark) {
                        CircularProgressIndicator(
                            color = LabBackground,
                            strokeWidth = 2.dp,
                            modifier = Modifier.height(18.dp).width(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Running 4 MB I/O Test...")
                    } else {
                        Icon(Icons.Filled.Speed, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Run 4 MB Storage I/O Test")
                    }
                }
                if (benchmarkMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(benchmarkMessage, style = MaterialTheme.typography.bodySmall, color = LabGreen)
                }
                storage.lastBenchmark?.let { bench ->
                    Spacer(modifier = Modifier.height(8.dp))
                    TelemetryRow(
                        label = "Sequential Write (${bench.payloadMb} MB)",
                        value = FormatUtils.formatDecimal(bench.writeSpeedMbPerSec, 1, "MB/s"),
                        valueColor = LabCyan
                    )
                    TelemetryRow(
                        label = "Sequential Read (${bench.payloadMb} MB)",
                        value = FormatUtils.formatDecimal(bench.readSpeedMbPerSec, 1, "MB/s"),
                        valueColor = LabGreen
                    )
                }
            }
        }

        if (storage.blockDevices.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Block Devices (/sys/block)",
                    subtitle = "${storage.blockDevices.size} BLOCK NODES"
                )
            }
            items(storage.blockDevices, key = { it.name }) { blk ->
                LabCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(blk.name, style = MaterialTheme.typography.titleSmall, color = LabCyan)
                        Text(
                            text = FormatUtils.formatBytes(blk.sizeBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = LabTextSecondary
                        )
                    }
                    Text(
                        text = "Rotational: ${blk.rotational?.let { if (it) "Yes" else "No (Solid State)" } ?: "N/A"} | Scheduler: ${blk.scheduler ?: "N/A"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextMuted
                    )
                }
            }
        }

        if (storage.mounts.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Filesystem Mounts (/proc/mounts)",
                    subtitle = "${storage.mounts.size} KEY MOUNTS"
                )
            }
            items(storage.mounts.take(12), key = { "${it.device}_${it.mountPoint}" }) { m ->
                LabCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(m.mountPoint, style = MaterialTheme.typography.titleSmall, color = LabCyan)
                        StatusPill(label = m.fsType.uppercase(), color = LabGreen)
                    }
                    Text(
                        text = "${m.device} (${m.options})",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextMuted
                    )
                }
            }
        }
    }
}
