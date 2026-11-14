package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.DataSourceOrigin
import com.example.domain.DiagnosticStatus
import com.example.domain.HealthSeverity
import com.example.domain.MetricMinMax
import com.example.domain.RefreshInterval
import com.example.domain.TelemetryHistoryPoint
import com.example.ui.theme.LabAmber
import com.example.ui.theme.LabAmberDim
import com.example.ui.theme.LabBackground
import com.example.ui.theme.LabBlue
import com.example.ui.theme.LabBorderStrong
import com.example.ui.theme.LabBorderSubtle
import com.example.ui.theme.LabCyan
import com.example.ui.theme.LabCyanDim
import com.example.ui.theme.LabGreen
import com.example.ui.theme.LabGreenDim
import com.example.ui.theme.LabRed
import com.example.ui.theme.LabRedDim
import com.example.ui.theme.LabSurface
import com.example.ui.theme.LabSurfaceHigh
import com.example.ui.theme.LabSurfaceVariant
import com.example.ui.theme.LabTextMuted
import com.example.ui.theme.LabTextPrimary
import com.example.ui.theme.LabTextSecondary
import com.example.ui.theme.MonoValueLarge
import com.example.ui.theme.MonoValueMedium
import com.example.ui.theme.MonoValueSmall
import com.example.utils.FormatUtils

fun severityColor(severity: HealthSeverity): Color = when (severity) {
    HealthSeverity.NORMAL -> LabGreen
    HealthSeverity.WARNING -> LabAmber
    HealthSeverity.CRITICAL -> LabRed
    HealthSeverity.UNAVAILABLE -> LabTextMuted
}

fun diagnosticStatusColor(status: DiagnosticStatus): Color = when (status) {
    DiagnosticStatus.PASS -> LabGreen
    DiagnosticStatus.WARNING -> LabAmber
    DiagnosticStatus.ERROR -> LabRed
    DiagnosticStatus.UNAVAILABLE -> LabTextMuted
}

@Composable
fun LabCard(
    modifier: Modifier = Modifier,
    borderColor: Color = LabBorderSubtle,
    backgroundColor: Color = LabSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            content = content
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (subtitle != null) {
                Text(
                    text = subtitle.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = LabCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = LabTextPrimary
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val bg = when (color) {
        LabGreen -> LabGreenDim
        LabAmber -> LabAmberDim
        LabRed -> LabRedDim
        LabCyan -> LabCyanDim
        else -> LabSurfaceHigh
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun SourceOriginTag(
    source: DataSourceOrigin,
    nodePath: String? = null,
    modifier: Modifier = Modifier
) {
    val tint = when (source) {
        DataSourceOrigin.ROOT_KERNEL_INTERFACE -> LabAmber
        DataSourceOrigin.UNAVAILABLE -> LabTextMuted
        DataSourceOrigin.SYSFS_USB, DataSourceOrigin.SYSFS_BATTERY, DataSourceOrigin.SYSFS_THERMAL,
        DataSourceOrigin.SYSFS_CPU, DataSourceOrigin.SYSFS_KGSL, DataSourceOrigin.SYSFS_DEVFREQ,
        DataSourceOrigin.SYSFS_BLOCK, DataSourceOrigin.SYSFS_POWER_SUPPLY -> LabCyan
        else -> LabBlue
    }
    Column(modifier = modifier) {
        Text(
            text = "SRC: ${source.label}",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!nodePath.isNullOrBlank()) {
            Text(
                text = nodePath,
                style = MonoValueSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = LabTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accentColor: Color = LabCyan,
    sourceLabel: String? = null,
    subtext: String? = null
) {
    LabCard(
        modifier = modifier,
        backgroundColor = LabSurfaceVariant,
        borderColor = LabBorderSubtle
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LabTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            style = MonoValueLarge,
            color = if (value == "N/A" || value == "UNAVAILABLE") LabTextMuted else accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtext.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtext,
                style = MaterialTheme.typography.bodySmall,
                color = LabTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!sourceLabel.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = LabTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TelemetryRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LabTextPrimary,
    source: DataSourceOrigin? = null,
    nodePath: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = LabTextSecondary,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = value,
                style = MonoValueMedium,
                color = if (value == "N/A" || value == "UNAVAILABLE") LabTextMuted else valueColor
            )
        }
        if (source != null) {
            SourceOriginTag(source = source, nodePath = nodePath)
        }
    }
}

@Composable
fun UsageProgressBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
        color = color,
        trackColor = LabSurfaceHigh
    )
}

@Composable
fun RealTimeLineChart(
    title: String,
    unit: String,
    points: List<TelemetryHistoryPoint>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    minMax: MetricMinMax? = null,
    decimals: Int = 1,
    emptyMessage: String = "Awaiting samples or sensor unavailable"
) {
    LabCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = LabTextPrimary
                )
                val latestText = points.lastOrNull()?.value?.let {
                    "${FormatUtils.formatDecimal(it, decimals)} $unit"
                } ?: "N/A"
                Text(
                    text = "Current: $latestText",
                    style = MonoValueSmall,
                    color = if (points.isEmpty()) LabTextMuted else lineColor
                )
            }
            if (minMax != null && (minMax.min != null || minMax.max != null)) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "MAX ${FormatUtils.formatDecimal(minMax.max, decimals)} $unit",
                        style = MonoValueSmall,
                        color = LabTextSecondary
                    )
                    Text(
                        text = "MIN ${FormatUtils.formatDecimal(minMax.min, decimals)} $unit",
                        style = MonoValueSmall,
                        color = LabTextMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (points.size < 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LabBackground)
                    .border(1.dp, LabBorderSubtle, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ShowChart,
                        contentDescription = null,
                        tint = LabTextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (points.size == 1) "Collecting second sample..." else emptyMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextMuted
                    )
                }
            }
        } else {
            val values = points.map { it.value }
            val rawMin = values.minOrNull() ?: 0.0
            val rawMax = values.maxOrNull() ?: 1.0
            val padding = ((rawMax - rawMin) * 0.15).coerceAtLeast(1.0)
            val yMin = rawMin - padding
            val yMax = rawMax + padding
            val ySpan = (yMax - yMin).coerceAtLeast(0.001)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LabBackground)
                    .border(1.dp, LabBorderSubtle, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val gridSteps = 3
                    for (i in 0..gridSteps) {
                        val y = h * (i.toFloat() / gridSteps.toFloat())
                        drawLine(
                            color = LabBorderSubtle.copy(alpha = 0.6f),
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    val linePath = Path()
                    val fillPath = Path()
                    val count = values.size

                    values.forEachIndexed { idx, v ->
                        val x = if (count <= 1) 0f else (idx.toFloat() / (count - 1).toFloat()) * w
                        val norm = ((v - yMin) / ySpan).toFloat().coerceIn(0f, 1f)
                        val y = h - (norm * h)
                        if (idx == 0) {
                            linePath.moveTo(x, y)
                            fillPath.moveTo(x, h)
                            fillPath.lineTo(x, y)
                        } else {
                            linePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                        if (idx == count - 1) {
                            fillPath.lineTo(x, h)
                            fillPath.close()
                        }
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(lineColor.copy(alpha = 0.28f), Color.Transparent),
                            startY = 0f,
                            endY = h
                        )
                    )
                    drawPath(
                        path = linePath,
                        color = lineColor,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )

                    val lastNorm = ((values.last() - yMin) / ySpan).toFloat().coerceIn(0f, 1f)
                    val lastY = h - (lastNorm * h)
                    drawCircle(
                        color = lineColor,
                        radius = 3.5.dp.toPx(),
                        center = Offset(w, lastY)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonitoringControlBar(
    refreshInterval: RefreshInterval,
    isPaused: Boolean,
    backgroundEnabled: Boolean,
    onSelectInterval: (RefreshInterval) -> Unit,
    onTogglePause: () -> Unit,
    onResetStats: () -> Unit,
    onToggleBackground: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    LabCard(
        modifier = modifier,
        backgroundColor = LabSurface,
        borderColor = LabBorderStrong
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RefreshInterval.entries.forEach { interval ->
                val selected = interval == refreshInterval
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
                        .clickable { onSelectInterval(interval) }
                        .testTag("interval_${interval.seconds}s")
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = interval.label,
                        style = MonoValueSmall,
                        color = if (selected) LabCyan else LabTextSecondary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPaused) LabAmberDim else LabGreenDim)
                    .border(
                        1.dp,
                        if (isPaused) LabAmber else LabGreen,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(onClick = onTogglePause)
                    .testTag("pause_resume_button")
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (isPaused) "Resume monitoring" else "Pause monitoring",
                        tint = if (isPaused) LabAmber else LabGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isPaused) "RESUME" else "LIVE",
                        style = MonoValueSmall,
                        color = if (isPaused) LabAmber else LabGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(LabSurfaceHigh)
                    .border(1.dp, LabBorderSubtle, RoundedCornerShape(8.dp))
                    .clickable(onClick = onResetStats)
                    .testTag("reset_stats_button")
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Reset min/max statistics",
                        tint = LabTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "RESET",
                        style = MonoValueSmall,
                        color = LabTextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = if (backgroundEnabled) LabCyan else LabTextMuted,
                    modifier = Modifier.size(16.dp)
                )
                Column {
                    Text(
                        text = "Background Monitoring",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextPrimary
                    )
                    Text(
                        text = "Keeps sampling when minimized (uses foreground notification)",
                        style = MaterialTheme.typography.labelSmall,
                        color = LabTextMuted
                    )
                }
            }
            Switch(
                checked = backgroundEnabled,
                onCheckedChange = onToggleBackground,
                modifier = Modifier.testTag("background_monitoring_switch"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LabBackground,
                    checkedTrackColor = LabCyan,
                    uncheckedThumbColor = LabTextSecondary,
                    uncheckedTrackColor = LabSurfaceHigh
                )
            )
        }
    }
}
