package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
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
import com.example.domain.KernelSystemInfo
import com.example.domain.RootStatus
import com.example.ui.components.LabCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.TelemetryRow
import com.example.ui.theme.LabAmber
import com.example.ui.theme.LabBackground
import com.example.ui.theme.LabCyan
import com.example.ui.theme.LabGreen
import com.example.ui.theme.LabTextMuted
import com.example.ui.theme.LabTextSecondary
import com.example.ui.theme.MonoValueSmall

@Composable
fun KernelRootScreen(
    kernel: KernelSystemInfo,
    isProbingRoot: Boolean,
    onReProbeRoot: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("kernel_root_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val rootColor = when (kernel.rootStatus) {
                RootStatus.ROOT_AVAILABLE -> LabGreen
                RootStatus.ROOT_DENIED -> LabAmber
                RootStatus.ROOT_NOT_FOUND -> LabTextMuted
            }
            LabCard(borderColor = rootColor.copy(alpha = 0.55f)) {
                SectionHeader(
                    title = "Root & Magisk Probe",
                    subtitle = "ACTUAL `su -c id` VERIFICATION",
                    trailing = {
                        StatusPill(label = kernel.rootStatus.label, color = rootColor)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                TelemetryRow(label = "Probe Output", value = kernel.rootProbeDetail)
                TelemetryRow(label = "Magisk Detection", value = kernel.magiskVersion ?: "Not detected")
                TelemetryRow(label = "SELinux Mode", value = kernel.selinuxStatus, valueColor = LabCyan)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onReProbeRoot,
                    enabled = !isProbingRoot,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LabCyan,
                        contentColor = LabBackground
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reprobe_root_button")
                ) {
                    if (isProbingRoot) {
                        CircularProgressIndicator(
                            color = LabBackground,
                            strokeWidth = 2.dp,
                            modifier = Modifier.height(18.dp).width(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Probing su -c id...")
                    } else {
                        Icon(Icons.Filled.Security, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Re-Verify Root Access (su -c id)")
                    }
                }
            }
        }

        item {
            LabCard(borderColor = LabGreen.copy(alpha = 0.4f)) {
                SectionHeader(
                    title = "Read-Only Root Security Policy",
                    subtitle = "ZERO KERNEL MODIFICATION GUARANTEE",
                    trailing = {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = LabGreen)
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Redmi 4X Hardware Lab is strictly READ-ONLY. It never modifies charging limits, CPU/GPU governors, thermal configs, sysfs parameters, or partitions, and enforces an explicit allowlist with timeouts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabTextSecondary
                )
            }
        }

        item {
            LabCard {
                SectionHeader(
                    title = "OS & Hardware Identity",
                    subtitle = "ANDROID BUILD & LINUX KERNEL"
                )
                Spacer(modifier = Modifier.height(6.dp))
                TelemetryRow(label = "Device Model", value = kernel.deviceModel)
                TelemetryRow(label = "Device Codename", value = kernel.deviceCodename, valueColor = LabCyan)
                TelemetryRow(label = "Board / Hardware", value = "${kernel.board} / ${kernel.hardware}")
                TelemetryRow(label = "Android Version", value = "${kernel.androidVersion} (API ${kernel.sdkInt})")
                TelemetryRow(label = "Security Patch", value = kernel.securityPatch)
                TelemetryRow(label = "Supported ABIs", value = kernel.supportedAbis.joinToString(", "))
                TelemetryRow(label = "Kernel Release", value = kernel.kernelVersion)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Build Fingerprint", style = MaterialTheme.typography.labelSmall, color = LabTextMuted)
                Text(kernel.buildFingerprint, style = MonoValueSmall, color = LabTextSecondary)
                if (!kernel.linuxProcVersion.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("/proc/version", style = MaterialTheme.typography.labelSmall, color = LabTextMuted)
                    Text(kernel.linuxProcVersion, style = MonoValueSmall, color = LabCyan)
                }
            }
        }

        item {
            LabCard {
                SectionHeader(
                    title = "Kernel Governors & Frequency Table",
                    subtitle = "CPUFREQ CAPABILITIES"
                )
                Spacer(modifier = Modifier.height(6.dp))
                TelemetryRow(
                    label = "Available Governors",
                    value = if (kernel.availableGovernors.isNotEmpty()) {
                        kernel.availableGovernors.joinToString(", ")
                    } else "UNAVAILABLE"
                )
                TelemetryRow(
                    label = "Available CPU Steps",
                    value = if (kernel.availableCpuFrequenciesMhz.isNotEmpty()) {
                        kernel.availableCpuFrequenciesMhz.joinToString(", ") { "${it.toInt()} MHz" }
                    } else "UNAVAILABLE"
                )
            }
        }

        item {
            LabCard {
                SectionHeader(
                    title = "Thermal Engine Configuration",
                    subtitle = "/vendor/etc/thermal-engine*.conf"
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (kernel.thermalConfigPreview != null) {
                    Text(
                        text = kernel.thermalConfigPreview,
                        style = MonoValueSmall,
                        color = LabTextSecondary
                    )
                } else {
                    Text(
                        text = "Thermal configuration file unreadable or not present on this ROM without root access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabTextMuted
                    )
                }
            }
        }
    }
}
