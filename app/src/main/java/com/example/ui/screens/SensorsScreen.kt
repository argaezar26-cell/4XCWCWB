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
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.HardwareSensorMetadata
import com.example.domain.LiveSensorSample
import com.example.ui.components.LabCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.LabAmber
import com.example.ui.theme.LabBackground
import com.example.ui.theme.LabCyan
import com.example.ui.theme.LabGreen
import com.example.ui.theme.LabTextMuted
import com.example.ui.theme.LabTextSecondary
import com.example.ui.theme.MonoValueMedium
import java.util.Locale

@Composable
fun SensorsScreen(
    sensors: List<HardwareSensorMetadata>,
    isLiveStreamActive: Boolean,
    liveSamples: Map<Int, LiveSensorSample>,
    onToggleLiveStream: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val availableCount = sensors.count { it.isAvailable }
    val unavailableCount = sensors.count { !it.isAvailable }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("sensors_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LabCard {
                SectionHeader(
                    title = "Live Sensor Telemetry Stream",
                    subtitle = "ANDROID SENSORMANAGER (SENSOR_DELAY_UI)",
                    trailing = {
                        StatusPill(
                            label = if (isLiveStreamActive) "STREAMING" else "IDLE",
                            color = if (isLiveStreamActive) LabGreen else LabTextMuted
                        )
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Uses battery-friendly UI sampling (~60ms throttled to 180ms UI updates) and auto-unregisters when leaving this screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabTextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { onToggleLiveStream(!isLiveStreamActive) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLiveStreamActive) LabAmber else LabCyan,
                        contentColor = LabBackground
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("toggle_sensor_stream_button")
                ) {
                    Icon(
                        imageVector = if (isLiveStreamActive) Icons.Filled.Stop else Icons.Filled.Sensors,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isLiveStreamActive) "Stop Live Sensor Stream" else "Start Live Sensor Values")
                }
            }
        }

        if (isLiveStreamActive) {
            if (liveSamples.isEmpty()) {
                item {
                    LabCard {
                        Text(
                            text = "Waiting for initial hardware sensor events...",
                            style = MaterialTheme.typography.bodySmall,
                            color = LabTextMuted
                        )
                    }
                }
            } else {
                items(liveSamples.values.toList(), key = { it.sensorType }) { sample ->
                    LabCard(borderColor = LabCyan.copy(alpha = 0.45f)) {
                        Text(sample.typeLabel, style = MaterialTheme.typography.titleSmall, color = LabCyan)
                        Text(sample.sensorName, style = MaterialTheme.typography.bodySmall, color = LabTextMuted)
                        Spacer(modifier = Modifier.height(6.dp))
                        val formattedValues = sample.values.mapIndexed { idx, v ->
                            val axis = when (idx) {
                                0 -> "X"
                                1 -> "Y"
                                2 -> "Z"
                                else -> "V$idx"
                            }
                            "$axis=${String.format(Locale.US, "%.3f", v)}"
                        }.joinToString("  ")
                        Text(
                            text = "$formattedValues (${sample.unit})",
                            style = MonoValueMedium,
                            color = LabGreen
                        )
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "Hardware Sensor Inventory",
                subtitle = "$availableCount AVAILABLE • $unavailableCount UNAVAILABLE"
            )
        }

        items(sensors, key = { "${it.type}_${it.name}" }) { sensor ->
            LabCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(sensor.typeLabel, style = MaterialTheme.typography.titleSmall, color = LabCyan)
                        Text(sensor.name, style = MaterialTheme.typography.bodyMedium, color = LabTextSecondary)
                    }
                    StatusPill(
                        label = if (sensor.isAvailable) "AVAILABLE" else "UNAVAILABLE",
                        color = if (sensor.isAvailable) LabGreen else LabTextMuted
                    )
                }
                if (sensor.isAvailable) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Vendor: ${sensor.vendor} (v${sensor.version}) | Res: ${sensor.resolution} | Max: ${sensor.maxRange} | Power: ${sensor.powerMa} mA",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextMuted
                    )
                }
            }
        }
    }
}
