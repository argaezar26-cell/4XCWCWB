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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.ThermalTelemetry
import com.example.domain.TelemetryHistoryState
import com.example.ui.components.LabCard
import com.example.ui.components.MetricTile
import com.example.ui.components.RealTimeLineChart
import com.example.ui.components.SectionHeader
import com.example.ui.components.SourceOriginTag
import com.example.ui.components.StatusPill
import com.example.ui.components.TelemetryRow
import com.example.ui.components.severityColor
import com.example.ui.theme.LabAmber
import com.example.ui.theme.LabCyan
import com.example.ui.theme.LabTextMuted
import com.example.ui.theme.LabTextSecondary
import com.example.ui.theme.MonoValueMedium
import com.example.utils.FormatUtils

@Composable
fun ThermalScreen(
    thermal: ThermalTelemetry,
    history: TelemetryHistoryState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("thermal_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LabCard(borderColor = severityColor(thermal.overallSeverity).copy(alpha = 0.55f)) {
                SectionHeader(
                    title = "Thermal Subsystem Status",
                    subtitle = "SYSFS THERMAL_ZONE & POWERMANAGER",
                    trailing = {
                        StatusPill(
                            label = thermal.overallSeverity.name,
                            color = severityColor(thermal.overallSeverity)
                        )
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                TelemetryRow(
                    label = "OS Thermal Status (PowerManager)",
                    value = thermal.osThermalStatusLabel
                )
                TelemetryRow(
                    label = "Thermal Headroom (10s forecast)",
                    value = thermal.osThermalHeadroom?.let { FormatUtils.formatDecimal(it.toDouble(), 2) } ?: "N/A"
                )
                TelemetryRow(
                    label = "Max Recorded Session Temp",
                    value = FormatUtils.formatTemp(thermal.maxRecordedTempC),
                    valueColor = LabAmber
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    label = "CPU / Cluster",
                    value = FormatUtils.formatTemp(thermal.cpuTempC),
                    accentColor = LabCyan,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "GPU",
                    value = FormatUtils.formatTemp(thermal.gpuTempC),
                    accentColor = LabCyan,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    label = "SoC",
                    value = FormatUtils.formatTemp(thermal.socTempC),
                    accentColor = LabAmber,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Battery",
                    value = FormatUtils.formatTemp(thermal.batteryTempC),
                    accentColor = severityColor(thermal.overallSeverity),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            RealTimeLineChart(
                title = "Peak Thermal Zone History (°C)",
                unit = "°C",
                points = history.maxThermalTempC,
                lineColor = severityColor(thermal.overallSeverity),
                minMax = history.thermalPeakMinMax,
                decimals = 1
            )
        }

        item {
            SectionHeader(
                title = "Thermal Sensor Inventory",
                subtitle = "${thermal.zones.size} ZONES UNDER /sys/class/thermal/"
            )
        }

        if (thermal.zones.isEmpty()) {
            item {
                LabCard {
                    Text(
                        text = "No thermal_zone* nodes readable via direct sysfs or current permissions. Relying on Battery temperature and PowerManager thermal status.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextMuted
                    )
                }
            }
        } else {
            items(thermal.zones, key = { it.zoneName }) { zone ->
                LabCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${zone.type} (${zone.category.label})",
                                style = MaterialTheme.typography.titleSmall,
                                color = LabCyan
                            )
                            Text(
                                text = "${zone.zoneName} • mode=${zone.mode ?: "N/A"} • policy=${zone.policy ?: "N/A"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = LabTextSecondary
                            )
                        }
                        Text(
                            text = FormatUtils.formatTemp(zone.temperatureC),
                            style = MonoValueMedium,
                            color = severityColor(zone.severity)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    SourceOriginTag(
                        source = zone.source,
                        nodePath = "${zone.path} (raw=${zone.rawValue ?: "N/A"}, unit=${zone.detectedUnit})"
                    )
                }
            }
        }
    }
}
