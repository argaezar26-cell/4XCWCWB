package com.example.ui.screens

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
import com.example.domain.ChargingTelemetry
import com.example.domain.TelemetryHistoryState
import com.example.ui.components.LabCard
import com.example.ui.components.MetricTile
import com.example.ui.components.RealTimeLineChart
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.TelemetryRow
import com.example.ui.theme.LabAmber
import com.example.ui.theme.LabBlue
import com.example.ui.theme.LabBorderSubtle
import com.example.ui.theme.LabCyan
import com.example.ui.theme.LabGreen
import com.example.ui.theme.LabTextMuted
import com.example.ui.theme.LabTextSecondary
import com.example.utils.FormatUtils

@Composable
fun ChargingScreen(
    charging: ChargingTelemetry,
    history: TelemetryHistoryState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("charging_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LabCard(
                borderColor = if (charging.isCharging) LabCyan.copy(alpha = 0.6f) else LabBorderSubtle
            ) {
                SectionHeader(
                    title = if (charging.isChargerConnected) "Charger Connected" else "Charger Disconnected",
                    subtitle = "CHARGING LAB • RAIL ISOLATION",
                    trailing = {
                        StatusPill(
                            label = if (charging.isCharging) "CHARGING" else "NOT CHARGING",
                            color = if (charging.isCharging) LabGreen else LabTextMuted
                        )
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                TelemetryRow(label = "Charging Source", value = charging.chargingSource, valueColor = LabCyan)
                TelemetryRow(label = "Charging Speed Class", value = charging.chargingSpeedLabel)
                TelemetryRow(
                    label = "Charging Temperature",
                    value = FormatUtils.formatTemp(charging.chargingTemperatureC.value),
                    source = charging.chargingTemperatureC.source
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    label = "Battery Power",
                    value = FormatUtils.formatWatts(charging.batteryPowerWatts.value),
                    accentColor = LabGreen,
                    subtext = "V_batt × I_batt",
                    sourceLabel = charging.batteryPowerWatts.source.label,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "USB Input Power",
                    value = FormatUtils.formatWatts(charging.usbPowerWatts.value),
                    accentColor = LabCyan,
                    subtext = if (charging.usbPowerWatts.value != null) "V_usb × I_usb" else "USB rail incomplete",
                    sourceLabel = charging.usbPowerWatts.source.label,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            LabCard {
                SectionHeader(
                    title = "USB Input Rail vs Battery Cell Rail",
                    subtitle = "STRICT SEPARATE MEASUREMENT"
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Power P = V × I is computed independently per rail. Battery voltage is never multiplied by USB input current.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabAmber
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = LabBorderSubtle)

                TelemetryRow(
                    label = "USB Input Voltage",
                    value = FormatUtils.formatMv(charging.usbVoltageMv.value),
                    valueColor = LabCyan,
                    source = charging.usbVoltageMv.source,
                    nodePath = charging.usbVoltageMv.nodePath
                )
                TelemetryRow(
                    label = "USB Input Current",
                    value = FormatUtils.formatSignedMa(charging.usbCurrentMa.value),
                    valueColor = LabCyan,
                    source = charging.usbCurrentMa.source,
                    nodePath = charging.usbCurrentMa.nodePath ?: charging.usbCurrentMa.note
                )
                TelemetryRow(
                    label = "USB Input Power",
                    value = FormatUtils.formatWatts(charging.usbPowerWatts.value),
                    valueColor = LabCyan,
                    source = charging.usbPowerWatts.source
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = LabBorderSubtle)

                TelemetryRow(
                    label = "Battery Cell Voltage",
                    value = FormatUtils.formatMv(charging.batteryVoltageMv.value),
                    valueColor = LabGreen,
                    source = charging.batteryVoltageMv.source,
                    nodePath = charging.batteryVoltageMv.nodePath
                )
                TelemetryRow(
                    label = "Battery Cell Current",
                    value = FormatUtils.formatSignedMa(charging.batteryCurrentMa.value),
                    valueColor = LabGreen,
                    source = charging.batteryCurrentMa.source,
                    nodePath = charging.batteryCurrentMa.nodePath
                )
                TelemetryRow(
                    label = "Battery Cell Power",
                    value = FormatUtils.formatWatts(charging.batteryPowerWatts.value),
                    valueColor = LabGreen,
                    source = charging.batteryPowerWatts.source
                )
            }
        }

        item {
            RealTimeLineChart(
                title = "Battery Power Real-Time (W)",
                unit = "W",
                points = history.chargingPowerWatts,
                lineColor = LabGreen,
                decimals = 2
            )
        }

        if (history.usbPowerWatts.isNotEmpty()) {
            item {
                RealTimeLineChart(
                    title = "USB Input Power Real-Time (W)",
                    unit = "W",
                    points = history.usbPowerWatts,
                    lineColor = LabBlue,
                    decimals = 2
                )
            }
        }

        item {
            SectionHeader(
                title = "Charging Session History",
                subtitle = "IN-APP RECORDED SESSIONS"
            )
        }

        val allSessions = buildList {
            charging.activeSession?.let { add(it) }
            addAll(charging.sessionHistory)
        }

        if (allSessions.isEmpty()) {
            item {
                LabCard {
                    Text(
                        text = "No charging sessions recorded yet. Connect a USB or AC charger to begin tracking session metrics.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextMuted
                    )
                }
            }
        } else {
            items(allSessions, key = { it.id }) { session ->
                LabCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Session #${session.id} • ${session.source}",
                            style = MaterialTheme.typography.titleSmall,
                            color = LabCyan
                        )
                        StatusPill(
                            label = if (session.endTimeMs == null) "ACTIVE" else "ENDED",
                            color = if (session.endTimeMs == null) LabGreen else LabTextMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Start: ${FormatUtils.formatTimestamp(session.startTimeMs)}" +
                            (session.endTimeMs?.let { " -> ${FormatUtils.formatTimestamp(it)}" } ?: " -> Now"),
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextSecondary
                    )
                    Text(
                        text = "SoC: ${session.startPercent ?: "N/A"}% -> ${session.latestPercent ?: "N/A"}% | Peak Batt: ${FormatUtils.formatWatts(session.peakBatteryPowerWatts)} | Max Temp: ${FormatUtils.formatTemp(session.maxTempCelsius)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextSecondary
                    )
                }
            }
        }
    }
}
