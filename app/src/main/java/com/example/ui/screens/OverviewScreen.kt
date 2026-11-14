package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.HardwareSnapshot
import com.example.domain.HealthSeverity
import com.example.domain.RootStatus
import com.example.domain.TelemetryHistoryState
import com.example.monitoring.LabPage
import com.example.ui.components.LabCard
import com.example.ui.components.MetricTile
import com.example.ui.components.RealTimeLineChart
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.TelemetryRow
import com.example.ui.components.UsageProgressBar
import com.example.ui.components.severityColor
import com.example.ui.theme.LabAmber
import com.example.ui.theme.LabBlue
import com.example.ui.theme.LabCyan
import com.example.ui.theme.LabGreen
import com.example.ui.theme.LabRed
import com.example.ui.theme.LabTextMuted
import com.example.ui.theme.LabTextPrimary
import com.example.ui.theme.LabTextSecondary
import com.example.utils.FormatUtils

@Composable
fun OverviewScreen(
    snapshot: HardwareSnapshot,
    history: TelemetryHistoryState,
    onNavigate: (LabPage) -> Unit,
    modifier: Modifier = Modifier
) {
    val k = snapshot.kernelSystem
    val b = snapshot.battery
    val c = snapshot.charging
    val t = snapshot.thermal
    val cpu = snapshot.cpu
    val gpu = snapshot.gpu
    val mem = snapshot.memory
    val st = snapshot.storage

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("overview_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LabCard {
                SectionHeader(
                    title = k.deviceModel,
                    subtitle = "TARGET HARDWARE IDENTITY",
                    trailing = {
                        val rootColor = when (k.rootStatus) {
                            RootStatus.ROOT_AVAILABLE -> LabGreen
                            RootStatus.ROOT_DENIED -> LabAmber
                            RootStatus.ROOT_NOT_FOUND -> LabTextMuted
                        }
                        StatusPill(label = k.rootStatus.label, color = rootColor)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                TelemetryRow(label = "Codename / Board", value = "${k.deviceCodename} / ${k.board}")
                TelemetryRow(label = "Android OS", value = "${k.androidVersion} (API ${k.sdkInt})")
                TelemetryRow(label = "Kernel Version", value = k.kernelVersion)
                TelemetryRow(label = "SELinux", value = k.selinuxStatus)
                if (k.magiskVersion != null) {
                    TelemetryRow(label = "Magisk", value = k.magiskVersion, valueColor = LabCyan)
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val cpuLoad = cpu.totalUtilizationPercent
                val cpuColor = when {
                    cpuLoad == null -> LabTextMuted
                    cpuLoad >= 85.0 -> LabRed
                    cpuLoad >= 65.0 -> LabAmber
                    else -> LabGreen
                }
                MetricTile(
                    label = "CPU Load",
                    value = FormatUtils.formatDecimal(cpuLoad, 1, "%"),
                    accentColor = cpuColor,
                    subtext = "${cpu.onlineCores}/${cpu.totalCores} cores online",
                    sourceLabel = cpu.source.label,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigate(LabPage.CPU_GPU) }
                        .testTag("overview_cpu_tile")
                )

                val gpuVal = gpu.currentFreqMhz?.let { FormatUtils.formatFreqMhz(it) } ?: gpu.gpuName
                MetricTile(
                    label = "GPU Clock / Info",
                    value = gpuVal,
                    accentColor = LabBlue,
                    subtext = gpu.gpuName,
                    sourceLabel = gpu.source.label,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigate(LabPage.CPU_GPU) }
                        .testTag("overview_gpu_tile")
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val battColor = severityColor(b.severity)
                MetricTile(
                    label = "Battery",
                    value = b.percentage.value?.let { "$it%" } ?: "N/A",
                    accentColor = battColor,
                    subtext = "${b.chargingState} • ${FormatUtils.formatTemp(b.temperatureC.value)}",
                    sourceLabel = b.percentage.source.label,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigate(LabPage.BATTERY) }
                        .testTag("overview_battery_tile")
                )

                val thermalColor = severityColor(t.overallSeverity)
                MetricTile(
                    label = "Thermal Peak",
                    value = FormatUtils.formatTemp(t.maxZoneTempC),
                    accentColor = thermalColor,
                    subtext = "OS: ${t.osThermalStatusLabel}",
                    sourceLabel = if (t.zones.isNotEmpty()) "${t.zones.size} sysfs zones" else "Battery/OS",
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigate(LabPage.THERMAL) }
                        .testTag("overview_thermal_tile")
                )
            }
        }

        item {
            LabCard(
                modifier = Modifier
                    .clickable { onNavigate(LabPage.CHARGING) }
                    .testTag("overview_power_card")
            ) {
                SectionHeader(
                    title = "Power & Charging Telemetry",
                    subtitle = "BATTERY & CHARGER RAIL",
                    trailing = {
                        StatusPill(
                            label = if (c.isCharging) "CHARGING" else b.status.value?.uppercase() ?: "UNKNOWN",
                            color = if (c.isCharging) LabCyan else severityColor(b.severity)
                        )
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                TelemetryRow(
                    label = "Battery Voltage",
                    value = FormatUtils.formatMv(b.voltageMv.value),
                    source = b.voltageMv.source,
                    nodePath = b.voltageMv.nodePath
                )
                TelemetryRow(
                    label = "Battery Current",
                    value = FormatUtils.formatSignedMa(b.currentNowMa.value),
                    source = b.currentNowMa.source,
                    nodePath = b.currentNowMa.nodePath
                )
                TelemetryRow(
                    label = "Charging Source",
                    value = c.chargingSource,
                    valueColor = if (c.isChargerConnected) LabCyan else LabTextSecondary
                )
                TelemetryRow(
                    label = "Battery Power (P=V×I)",
                    value = FormatUtils.formatWatts(c.batteryPowerWatts.value)
                )
            }
        }

        item {
            LabCard(
                modifier = Modifier
                    .clickable { onNavigate(LabPage.MEMORY_STORAGE) }
                    .testTag("overview_memory_storage_card")
            ) {
                SectionHeader(
                    title = "Memory & Internal Storage",
                    subtitle = "SYSTEM CAPACITY"
                )
                Spacer(modifier = Modifier.height(10.dp))

                val ramColor = when {
                    mem.isLowMemory || mem.utilizationPercent >= 90.0 -> LabRed
                    mem.utilizationPercent >= 78.0 -> LabAmber
                    else -> LabGreen
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("RAM Usage", style = MaterialTheme.typography.bodyMedium, color = LabTextSecondary)
                    Text(
                        "${FormatUtils.formatBytes(mem.usedRamBytes)} / ${FormatUtils.formatBytes(mem.totalRamBytes)} (${FormatUtils.formatDecimal(mem.utilizationPercent, 1, "%")})",
                        style = MaterialTheme.typography.labelMedium,
                        color = ramColor
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                UsageProgressBar(
                    progress = (mem.utilizationPercent / 100.0).toFloat(),
                    color = ramColor
                )

                Spacer(modifier = Modifier.height(12.dp))

                val storageColor = when {
                    st.usagePercent >= 92.0 -> LabRed
                    st.usagePercent >= 80.0 -> LabAmber
                    else -> LabCyan
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Internal Storage (/data)", style = MaterialTheme.typography.bodyMedium, color = LabTextSecondary)
                    Text(
                        "${FormatUtils.formatBytes(st.internalUsedBytes)} / ${FormatUtils.formatBytes(st.internalTotalBytes)} (${FormatUtils.formatDecimal(st.usagePercent, 1, "%")})",
                        style = MaterialTheme.typography.labelMedium,
                        color = storageColor
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                UsageProgressBar(
                    progress = (st.usagePercent / 100.0).toFloat(),
                    color = storageColor
                )
            }
        }

        item {
            RealTimeLineChart(
                title = "CPU Utilization Stream",
                unit = "%",
                points = history.cpuUtilization,
                lineColor = LabCyan,
                minMax = history.cpuLoadMinMax
            )
        }

        item {
            RealTimeLineChart(
                title = "Battery Temperature Stream",
                unit = "°C",
                points = history.batteryTempC,
                lineColor = if (b.severity == HealthSeverity.CRITICAL) LabRed else LabGreen,
                minMax = history.batteryTempMinMax
            )
        }
    }
}
