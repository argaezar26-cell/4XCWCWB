package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.ThermalTelemetry
import com.example.domain.ThermalZoneCategory
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
import com.example.ui.theme.LabBorderSubtle
import com.example.ui.theme.LabCyan
import com.example.ui.theme.LabCyanDim
import com.example.ui.theme.LabSurfaceHigh
import com.example.ui.theme.LabTextMuted
import com.example.ui.theme.LabTextSecondary
import com.example.ui.theme.MonoValueMedium
import com.example.ui.theme.MonoValueSmall
import com.example.utils.FormatUtils

@Composable
fun ThermalScreen(
    thermal: ThermalTelemetry,
    history: TelemetryHistoryState,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf("All") }
    val filterOptions = listOf("All", "Active", "CPU", "GPU", "SoC", "Battery", "PMIC")

    val filteredZones = remember(thermal.zones, selectedFilter) {
        when (selectedFilter) {
            "Active" -> thermal.zones.filter { it.temperatureC != null }
            "CPU" -> thermal.zones.filter { it.category == ThermalZoneCategory.CPU }
            "GPU" -> thermal.zones.filter { it.category == ThermalZoneCategory.GPU }
            "SoC" -> thermal.zones.filter { it.category == ThermalZoneCategory.SOC }
            "Battery" -> thermal.zones.filter { it.category == ThermalZoneCategory.BATTERY }
            "PMIC" -> thermal.zones.filter { it.category == ThermalZoneCategory.PMIC_BOARD }
            else -> thermal.zones
        }
    }

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
                subtitle = "${filteredZones.size}/${thermal.zones.size} ZONES UNDER /sys/class/thermal/"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                filterOptions.forEach { option ->
                    val selected = option == selectedFilter
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) LabCyanDim else LabSurfaceHigh)
                            .border(
                                1.dp,
                                if (selected) LabCyan else LabBorderSubtle,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { selectedFilter = option }
                            .testTag("thermal_filter_${option.lowercase()}")
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = option,
                            style = MonoValueSmall,
                            color = if (selected) LabCyan else LabTextSecondary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        if (filteredZones.isEmpty()) {
            item {
                LabCard {
                    Text(
                        text = if (thermal.zones.isEmpty()) {
                            "No thermal_zone* nodes readable via direct sysfs or current permissions. Relying on Battery temperature and PowerManager thermal status."
                        } else {
                            "No thermal zones match filter '$selectedFilter'."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextMuted
                    )
                }
            }
        } else {
            items(filteredZones, key = { it.zoneName }) { zone ->
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
                    if (zone.tripPoints.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val tripsSummary = zone.tripPoints.joinToString(" | ") { tp ->
                            val tLabel = tp.type ?: "trip${tp.index}"
                            "$tLabel: ${FormatUtils.formatTemp(tp.tempCelsius)}"
                        }
                        Text(
                            text = "Trips: $tripsSummary",
                            style = MonoValueSmall,
                            color = LabAmber
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
