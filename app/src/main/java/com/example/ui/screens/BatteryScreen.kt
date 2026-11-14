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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.BatteryTelemetry
import com.example.domain.ChargingTelemetry
import com.example.domain.TelemetryHistoryState
import com.example.ui.components.LabCard
import com.example.ui.components.MetricTile
import com.example.ui.components.RealTimeLineChart
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.TelemetryRow
import com.example.ui.components.severityColor
import com.example.ui.theme.LabAmber
import com.example.ui.theme.LabBlue
import com.example.ui.theme.LabBorderSubtle
import com.example.ui.theme.LabCyan
import com.example.ui.theme.LabGreen
import com.example.ui.theme.LabTextMuted
import com.example.ui.theme.LabTextSecondary
import com.example.utils.FormatUtils

@Composable
fun BatteryScreen(
    battery: BatteryTelemetry,
    charging: ChargingTelemetry,
    history: TelemetryHistoryState,
    onOpenChargingLab: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("battery_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    label = "State of Charge",
                    value = battery.percentage.value?.let { "$it%" } ?: "N/A",
                    accentColor = severityColor(battery.severity),
                    subtext = battery.chargingState,
                    sourceLabel = battery.percentage.source.label,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Cell Voltage",
                    value = FormatUtils.formatMv(battery.voltageMv.value),
                    accentColor = LabCyan,
                    subtext = "Temp: ${FormatUtils.formatTemp(battery.temperatureC.value)}",
                    sourceLabel = battery.voltageMv.source.label,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            LabCard {
                SectionHeader(
                    title = "Battery Cell & Coulomb Telemetry",
                    subtitle = "SYSFS POWER_SUPPLY & BATTERYMANAGER",
                    trailing = {
                        StatusPill(
                            label = battery.status.value?.uppercase() ?: "UNKNOWN",
                            color = severityColor(battery.severity)
                        )
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                TelemetryRow(
                    label = "Battery Current (Instant)",
                    value = FormatUtils.formatSignedMa(battery.currentNowMa.value),
                    valueColor = LabCyan,
                    source = battery.currentNowMa.source,
                    nodePath = battery.currentNowMa.nodePath
                )
                Text(
                    text = battery.currentDirectionNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = LabAmber
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = LabBorderSubtle)

                TelemetryRow(
                    label = "Battery Current (Average)",
                    value = FormatUtils.formatSignedMa(battery.currentAvgMa.value),
                    source = battery.currentAvgMa.source,
                    nodePath = battery.currentAvgMa.nodePath
                )
                TelemetryRow(
                    label = "Battery Temperature",
                    value = FormatUtils.formatTemp(battery.temperatureC.value),
                    source = battery.temperatureC.source,
                    nodePath = battery.temperatureC.nodePath
                )
                TelemetryRow(
                    label = "Android Health Status",
                    value = battery.healthAndroid.value ?: "UNAVAILABLE",
                    source = battery.healthAndroid.source,
                    nodePath = battery.healthAndroid.nodePath
                )
                TelemetryRow(
                    label = "Chemistry / Technology",
                    value = battery.technology.value ?: "UNAVAILABLE",
                    source = battery.technology.source
                )
                TelemetryRow(
                    label = "Charge Counter",
                    value = FormatUtils.formatDecimal(battery.chargeCounterMah.value, 0, "mAh"),
                    source = battery.chargeCounterMah.source,
                    nodePath = battery.chargeCounterMah.nodePath
                )
                TelemetryRow(
                    label = "Charge Full (Learned)",
                    value = FormatUtils.formatDecimal(battery.chargeFullMah.value, 0, "mAh"),
                    source = battery.chargeFullMah.source,
                    nodePath = battery.chargeFullMah.nodePath
                )
                TelemetryRow(
                    label = "Charge Full Design",
                    value = FormatUtils.formatDecimal(battery.chargeFullDesignMah.value, 0, "mAh"),
                    source = battery.chargeFullDesignMah.source,
                    nodePath = battery.chargeFullDesignMah.nodePath
                )
                TelemetryRow(
                    label = "Cycle Count",
                    value = battery.cycleCount.value?.toString() ?: "UNAVAILABLE",
                    source = battery.cycleCount.source,
                    nodePath = battery.cycleCount.nodePath
                )
            }
        }

        item {
            LabCard(
                borderColor = if (battery.estimatedHealth != null) LabGreen.copy(alpha = 0.5f) else LabBorderSubtle
            ) {
                SectionHeader(
                    title = "Estimated Battery Health",
                    subtitle = "CAPACITY RETENTION ESTIMATE",
                    trailing = {
                        val est = battery.estimatedHealth
                        StatusPill(
                            label = if (est != null) "${FormatUtils.formatDecimal(est.estimatedPercent, 1)}%" else "UNAVAILABLE",
                            color = when {
                                est == null -> LabTextMuted
                                est.estimatedPercent >= 80.0 -> LabGreen
                                est.estimatedPercent >= 65.0 -> LabAmber
                                else -> com.example.ui.theme.LabRed
                            }
                        )
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                val est = battery.estimatedHealth
                if (est != null) {
                    Text(
                        text = "Learned full capacity ${FormatUtils.formatDecimal(est.chargeFullMah, 0, "mAh")} vs design capacity ${FormatUtils.formatDecimal(est.chargeFullDesignMah, 0, "mAh")}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabTextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = est.disclaimer,
                        style = MaterialTheme.typography.bodySmall,
                        color = LabAmber
                    )
                } else {
                    Text(
                        text = "Kernel did not expose both charge_full and charge_full_design nodes. Estimation unavailable on this ROM/kernel without root or fuel-gauge sysfs exposure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextMuted
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Open Charging Lab for USB vs Battery power rail separation ->",
                    style = MaterialTheme.typography.labelMedium,
                    color = LabCyan,
                    modifier = Modifier
                        .clickable(onClick = onOpenChargingLab)
                        .padding(vertical = 4.dp)
                        .testTag("battery_to_charging_link")
                )
            }
        }

        item {
            RealTimeLineChart(
                title = "1. Battery Current (mA)",
                unit = "mA",
                points = history.batteryCurrentMa,
                lineColor = LabCyan,
                minMax = history.batteryCurrentMinMax,
                decimals = 0
            )
        }
        item {
            RealTimeLineChart(
                title = "2. Battery Voltage (mV)",
                unit = "mV",
                points = history.batteryVoltageMv,
                lineColor = LabBlue,
                minMax = history.batteryVoltageMinMax,
                decimals = 0
            )
        }
        item {
            RealTimeLineChart(
                title = "3. Battery Temperature (°C)",
                unit = "°C",
                points = history.batteryTempC,
                lineColor = LabGreen,
                minMax = history.batteryTempMinMax,
                decimals = 1
            )
        }
        item {
            RealTimeLineChart(
                title = "4. Battery Percentage (%)",
                unit = "%",
                points = history.batteryPercent,
                lineColor = LabAmber,
                decimals = 0
            )
        }
        item {
            val powerPoints = if (history.usbPowerWatts.isNotEmpty()) history.usbPowerWatts else history.chargingPowerWatts
            val powerTitle = if (history.usbPowerWatts.isNotEmpty()) {
                "5. USB Input Power (W)"
            } else {
                "5. Battery Rail Power |V_batt × I_batt| (W)"
            }
            RealTimeLineChart(
                title = powerTitle,
                unit = "W",
                points = powerPoints,
                lineColor = LabCyan,
                decimals = 2,
                emptyMessage = if (charging.isCharging) "Waiting for power samples..." else "Connect charger or monitor load for power trace"
            )
        }

        item {
            SectionHeader(
                title = "Detected /sys/class/power_supply/ Nodes",
                subtitle = "${battery.detectedPowerSupplies.size} KERNEL NODES DISCOVERED"
            )
        }

        if (battery.detectedPowerSupplies.isEmpty()) {
            item {
                LabCard {
                    Text(
                        text = "No directories readable under /sys/class/power_supply/ (SELinux restricted or unavailable). Using Android BatteryManager API.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextMuted
                    )
                }
            }
        } else {
            items(battery.detectedPowerSupplies, key = { it.path }) { node ->
                LabCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(node.name, style = MaterialTheme.typography.titleSmall, color = LabCyan)
                            Text(node.path, style = MaterialTheme.typography.labelSmall, color = LabTextMuted)
                        }
                        StatusPill(
                            label = node.type ?: "Unknown Type",
                            color = if (node.online == true || node.present == true) LabGreen else LabTextMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            append("Online: ${node.online ?: "N/A"} | Status: ${node.status ?: "N/A"}")
                            node.voltageMv?.let { append(" | V=${FormatUtils.formatMv(it)}") }
                            node.currentMa?.let { append(" | I=${FormatUtils.formatSignedMa(it)}") }
                            node.inputCurrentMa?.let { append(" | I_in=${FormatUtils.formatSignedMa(it)}") }
                            node.tempCelsius?.let { append(" | T=${FormatUtils.formatTemp(it)}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextSecondary
                    )
                }
            }
        }
    }
}
