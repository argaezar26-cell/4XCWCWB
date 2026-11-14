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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.DiagnosticReport
import com.example.ui.components.LabCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.diagnosticStatusColor
import com.example.ui.theme.LabAmber
import com.example.ui.theme.LabBackground
import com.example.ui.theme.LabBlue
import com.example.ui.theme.LabCyan
import com.example.ui.theme.LabGreen
import com.example.ui.theme.LabRed
import com.example.ui.theme.LabSurface
import com.example.ui.theme.LabTextMuted
import com.example.ui.theme.LabTextSecondary
import com.example.ui.theme.MonoValueSmall
import com.example.utils.FormatUtils

@Composable
fun DiagnosticsScreen(
    report: DiagnosticReport?,
    onRunDiagnostics: () -> Unit,
    onExportTxt: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit = {},
    onCopyTxt: () -> Unit,
    onShareTxt: () -> Unit = {},
    buildPreviewText: () -> String?,
    statusBanner: String?,
    modifier: Modifier = Modifier
) {
    var showPreviewDialog by remember { mutableStateOf(false) }

    if (showPreviewDialog) {
        val preview = buildPreviewText() ?: "No diagnostic report available."
        AlertDialog(
            onDismissRequest = { showPreviewDialog = false },
            containerColor = LabSurface,
            title = { Text("Diagnostic TXT Report Preview", color = LabCyan) },
            text = {
                Text(
                    text = preview,
                    style = MonoValueSmall,
                    color = LabTextSecondary,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showPreviewDialog = false }) {
                    Text("Close", color = LabCyan)
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("diagnostics_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LabCard {
                SectionHeader(
                    title = "Hardware Diagnostic Center",
                    subtitle = "AUTOMATED VERIFICATION SUITE"
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Evaluates battery, charging rails, thermal zones, CPU, GPU, RAM/zRAM, eMMC/storage, sensors, root access, and kernel info. Unavailable optional nodes are never flagged as hardware damage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabTextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onRunDiagnostics,
                    colors = ButtonDefaults.buttonColors(containerColor = LabCyan, contentColor = LabBackground),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("run_diagnostics_button")
                ) {
                    Icon(Icons.Filled.Assessment, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Run Full Hardware Check")
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onExportTxt,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_txt_button")
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = LabGreen)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("TXT", color = LabGreen)
                    }
                    OutlinedButton(
                        onClick = onExportCsv,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_csv_button")
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = LabCyan)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("CSV", color = LabCyan)
                    }
                    OutlinedButton(
                        onClick = onExportJson,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_json_button")
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = LabBlue)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("JSON", color = LabBlue)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showPreviewDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Visibility, contentDescription = null, tint = LabTextSecondary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Preview", color = LabTextSecondary)
                    }
                    OutlinedButton(
                        onClick = onCopyTxt,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = LabTextSecondary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", color = LabTextSecondary)
                    }
                    OutlinedButton(
                        onClick = onShareTxt,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("share_txt_button")
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, tint = LabCyan)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share", color = LabCyan)
                    }
                }
                if (statusBanner != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(statusBanner, style = MaterialTheme.typography.bodySmall, color = LabGreen)
                }
            }
        }

        if (report != null) {
            item {
                LabCard {
                    SectionHeader(
                        title = "Summary (${FormatUtils.formatTimestamp(report.generatedAtMs)})",
                        subtitle = report.deviceLabel
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusPill(label = "PASS ${report.passCount}", color = LabGreen)
                        StatusPill(label = "WARN ${report.warningCount}", color = LabAmber)
                        StatusPill(label = "N/A ${report.unavailableCount}", color = LabTextMuted)
                        StatusPill(label = "ERR ${report.errorCount}", color = LabRed)
                    }
                }
            }

            items(report.checks, key = { it.id }) { check ->
                val color = diagnosticStatusColor(check.status)
                LabCard(borderColor = color.copy(alpha = 0.4f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(check.category, style = MaterialTheme.typography.titleSmall, color = LabCyan)
                            Text(check.summary, style = MaterialTheme.typography.bodyMedium, color = LabTextSecondary)
                        }
                        StatusPill(label = check.status.name, color = color)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = check.technicalReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextMuted
                    )
                }
            }
        }
    }
}
