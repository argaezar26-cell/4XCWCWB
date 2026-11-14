package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.monitoring.HardwareLabUiState
import com.example.monitoring.LabPage
import com.example.ui.components.MonitoringControlBar
import com.example.ui.components.StatusPill
import com.example.ui.screens.BatteryScreen
import com.example.ui.screens.ChargingScreen
import com.example.ui.screens.CpuGpuScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.KernelRootScreen
import com.example.ui.screens.MemoryStorageScreen
import com.example.ui.screens.OverviewScreen
import com.example.ui.screens.SensorsScreen
import com.example.ui.screens.ThermalScreen
import com.example.ui.theme.LabAmber
import com.example.ui.theme.LabBackground
import com.example.ui.theme.LabBorderSubtle
import com.example.ui.theme.LabCyan
import com.example.ui.theme.LabCyanDim
import com.example.ui.theme.LabGreen
import com.example.ui.theme.LabSurface
import com.example.ui.theme.LabSurfaceHigh
import com.example.ui.theme.LabTextMuted
import com.example.ui.theme.LabTextPrimary
import com.example.ui.theme.LabTextSecondary
import com.example.ui.theme.MonoValueSmall
import com.example.utils.FormatUtils
import kotlinx.coroutines.launch

private fun iconForPage(page: LabPage): ImageVector = when (page) {
    LabPage.OVERVIEW -> Icons.Filled.Dashboard
    LabPage.BATTERY -> Icons.Filled.BatteryChargingFull
    LabPage.CHARGING -> Icons.Filled.Bolt
    LabPage.THERMAL -> Icons.Filled.Thermostat
    LabPage.CPU_GPU -> Icons.Filled.Memory
    LabPage.MEMORY_STORAGE -> Icons.Filled.Storage
    LabPage.SENSORS -> Icons.Filled.Sensors
    LabPage.KERNEL_ROOT -> Icons.Filled.DeveloperBoard
    LabPage.DIAGNOSTICS -> Icons.Filled.Assessment
}

@Composable
fun HardwareLabApp(
    state: HardwareLabUiState,
    onSelectPage: (LabPage) -> Unit,
    onSelectInterval: (com.example.domain.RefreshInterval) -> Unit,
    onTogglePause: () -> Unit,
    onResetStats: () -> Unit,
    onToggleBackground: (Boolean) -> Unit,
    onReProbeRoot: () -> Unit,
    onRunBenchmark: () -> Unit,
    onToggleSensorLiveStream: (Boolean) -> Unit,
    onRunDiagnostics: () -> Unit,
    onBuildTextReport: () -> String?,
    onBuildCsvReport: () -> String?,
    onBuildJsonReport: () -> String? = { null }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showControls by remember { mutableStateOf(false) }
    var exportStatusMessage by remember { mutableStateOf<String?>(null) }

    // Return to Overview on system Back when inside any secondary Lab page
    BackHandler(enabled = state.currentPage != LabPage.OVERVIEW) {
        onSelectPage(LabPage.OVERVIEW)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onToggleBackground(true)
        } else {
            onToggleBackground(false)
        }
    }

    val handleBackgroundToggle: (Boolean) -> Unit = { enable ->
        if (!enable) {
            onToggleBackground(false)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                onToggleBackground(true)
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            onToggleBackground(true)
        }
    }

    val createTxtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val text = onBuildTextReport() ?: return@rememberLauncherForActivityResult
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
                exportStatusMessage = "TXT report saved successfully."
            } catch (e: Exception) {
                exportStatusMessage = "Failed to write TXT: ${e.message}"
            }
        }
    }

    val createCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val csv = onBuildCsvReport() ?: return@rememberLauncherForActivityResult
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(csv.toByteArray(Charsets.UTF_8))
                }
                exportStatusMessage = "CSV report saved successfully."
            } catch (e: Exception) {
                exportStatusMessage = "Failed to write CSV: ${e.message}"
            }
        }
    }

    val createJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val json = onBuildJsonReport() ?: return@rememberLauncherForActivityResult
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                }
                exportStatusMessage = "JSON report saved successfully."
            } catch (e: Exception) {
                exportStatusMessage = "Failed to write JSON: ${e.message}"
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = LabSurface,
                drawerContentColor = LabTextPrimary
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        text = "REDMI 4X HARDWARE LAB",
                        style = MaterialTheme.typography.titleMedium,
                        color = LabCyan
                    )
                    Text(
                        text = "v2.0.0 • DEEP HARDWARE & KERNEL LAB",
                        style = MaterialTheme.typography.labelSmall,
                        color = LabTextMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = LabBorderSubtle)
                    Spacer(modifier = Modifier.height(8.dp))

                    LabPage.entries.forEach { page ->
                        NavigationDrawerItem(
                            icon = {
                                Icon(iconForPage(page), contentDescription = null)
                            },
                            label = { Text(page.title) },
                            selected = state.currentPage == page,
                            onClick = {
                                onSelectPage(page)
                                scope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = LabCyanDim,
                                selectedIconColor = LabCyan,
                                selectedTextColor = LabTextPrimary,
                                unselectedIconColor = LabTextSecondary,
                                unselectedTextColor = LabTextSecondary
                            ),
                            modifier = Modifier.testTag("drawer_nav_${page.route}")
                        )
                    }
                }
            }
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(LabBackground)
        ) {
            val isWideScreen = maxWidth >= 700.dp

            Scaffold(
                containerColor = LabBackground,
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LabSurface)
                            .statusBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                IconButton(
                                    onClick = { scope.launch { drawerState.open() } },
                                    modifier = Modifier.testTag("open_drawer_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Menu,
                                        contentDescription = "Open lab navigation menu",
                                        tint = LabCyan
                                    )
                                }
                                Column {
                                    Text(
                                        text = "REDMI 4X HARDWARE LAB",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = LabTextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val ts = state.snapshot?.timestampMs?.let { FormatUtils.formatTimestamp(it) } ?: "--:--:--"
                                    Text(
                                        text = "${state.currentPage.title} • $ts • ${state.refreshInterval.label}",
                                        style = MonoValueSmall,
                                        color = LabTextSecondary
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                StatusPill(
                                    label = if (state.isPaused) "PAUSED" else "LIVE",
                                    color = if (state.isPaused) LabAmber else LabGreen
                                )
                                IconButton(
                                    onClick = { showControls = !showControls },
                                    modifier = Modifier.testTag("toggle_controls_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Tune,
                                        contentDescription = "Toggle monitoring controls",
                                        tint = if (showControls) LabCyan else LabTextSecondary
                                    )
                                }
                            }
                        }

                        if (!isWideScreen) {
                            // 1-tap horizontal lab selector strip so all 9 pages are directly accessible
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                LabPage.entries.forEach { page ->
                                    val selected = page == state.currentPage
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
                                            .clickable { onSelectPage(page) }
                                            .testTag("lab_tab_${page.route}")
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(
                                                imageVector = iconForPage(page),
                                                contentDescription = null,
                                                tint = if (selected) LabCyan else LabTextSecondary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = page.shortLabel,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (selected) LabCyan else LabTextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (showControls) {
                            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                MonitoringControlBar(
                                    refreshInterval = state.refreshInterval,
                                    isPaused = state.isPaused,
                                    backgroundEnabled = state.backgroundMonitoringEnabled,
                                    onSelectInterval = onSelectInterval,
                                    onTogglePause = onTogglePause,
                                    onResetStats = onResetStats,
                                    onToggleBackground = handleBackgroundToggle
                                )
                            }
                        }
                        HorizontalDivider(color = LabBorderSubtle)
                    }
                },
                bottomBar = {
                    if (!isWideScreen) {
                        val quickTabs = listOf(
                            LabPage.OVERVIEW,
                            LabPage.BATTERY,
                            LabPage.THERMAL,
                            LabPage.CPU_GPU,
                            LabPage.DIAGNOSTICS
                        )
                        NavigationBar(
                            containerColor = LabSurface,
                            modifier = Modifier.navigationBarsPadding()
                        ) {
                            quickTabs.forEach { page ->
                                val selected = state.currentPage == page ||
                                    (page == LabPage.BATTERY && state.currentPage == LabPage.CHARGING)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { onSelectPage(page) },
                                    icon = { Icon(iconForPage(page), contentDescription = page.title) },
                                    label = { Text(page.shortLabel, maxLines = 1) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = LabCyan,
                                        selectedTextColor = LabCyan,
                                        indicatorColor = LabCyanDim,
                                        unselectedIconColor = LabTextMuted,
                                        unselectedTextColor = LabTextMuted
                                    ),
                                    modifier = Modifier.testTag("bottom_nav_${page.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isWideScreen) {
                        NavigationRail(
                            containerColor = LabSurface,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            LabPage.entries.forEach { page ->
                                NavigationRailItem(
                                    selected = state.currentPage == page,
                                    onClick = { onSelectPage(page) },
                                    icon = { Icon(iconForPage(page), contentDescription = page.title) },
                                    label = { Text(page.shortLabel) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = LabCyan,
                                        selectedTextColor = LabCyan,
                                        indicatorColor = LabCyanDim,
                                        unselectedIconColor = LabTextMuted,
                                        unselectedTextColor = LabTextMuted
                                    )
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        val snapshot = state.snapshot
                        if (snapshot == null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = LabCyan)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Probing kernel sysfs & hardware interfaces...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LabTextSecondary
                                )
                                if (state.lastError != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Last read error: ${state.lastError}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LabAmber
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .widthIn(max = 920.dp)
                            ) {
                                when (state.currentPage) {
                                    LabPage.OVERVIEW -> OverviewScreen(
                                        snapshot = snapshot,
                                        history = state.history,
                                        onNavigate = onSelectPage
                                    )
                                    LabPage.BATTERY -> BatteryScreen(
                                        battery = snapshot.battery,
                                        charging = snapshot.charging,
                                        history = state.history,
                                        onOpenChargingLab = { onSelectPage(LabPage.CHARGING) }
                                    )
                                    LabPage.CHARGING -> ChargingScreen(
                                        charging = snapshot.charging,
                                        history = state.history
                                    )
                                    LabPage.THERMAL -> ThermalScreen(
                                        thermal = snapshot.thermal,
                                        history = state.history
                                    )
                                    LabPage.CPU_GPU -> CpuGpuScreen(
                                        cpu = snapshot.cpu,
                                        gpu = snapshot.gpu,
                                        history = state.history
                                    )
                                    LabPage.MEMORY_STORAGE -> MemoryStorageScreen(
                                        memory = snapshot.memory,
                                        storage = snapshot.storage,
                                        history = state.history,
                                        isRunningBenchmark = state.isRunningBenchmark,
                                        benchmarkMessage = state.benchmarkMessage,
                                        onRunBenchmark = onRunBenchmark
                                    )
                                    LabPage.SENSORS -> SensorsScreen(
                                        sensors = snapshot.sensors,
                                        isLiveStreamActive = state.isSensorLiveStreamActive,
                                        liveSamples = state.liveSensorSamples,
                                        onToggleLiveStream = onToggleSensorLiveStream
                                    )
                                    LabPage.KERNEL_ROOT -> KernelRootScreen(
                                        kernel = snapshot.kernelSystem,
                                        isProbingRoot = state.isProbingRoot,
                                        onReProbeRoot = onReProbeRoot
                                    )
                                    LabPage.DIAGNOSTICS -> DiagnosticsScreen(
                                        report = state.diagnosticReport,
                                        onRunDiagnostics = {
                                            exportStatusMessage = null
                                            onRunDiagnostics()
                                        },
                                        onExportTxt = {
                                            val ts = FormatUtils.formatDateTime(System.currentTimeMillis())
                                                .replace(" ", "_").replace(":", "-")
                                            createTxtLauncher.launch("redmi4x_hwlab_$ts.txt")
                                        },
                                        onExportCsv = {
                                            val ts = FormatUtils.formatDateTime(System.currentTimeMillis())
                                                .replace(" ", "_").replace(":", "-")
                                            createCsvLauncher.launch("redmi4x_hwlab_$ts.csv")
                                        },
                                        onExportJson = {
                                            val ts = FormatUtils.formatDateTime(System.currentTimeMillis())
                                                .replace(" ", "_").replace(":", "-")
                                            createJsonLauncher.launch("redmi4x_hwlab_$ts.json")
                                        },
                                        onCopyTxt = {
                                            val text = onBuildTextReport()
                                            if (text != null) {
                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                cm?.setPrimaryClip(ClipData.newPlainText("Hardware Diagnostic Report", text))
                                                exportStatusMessage = "Report copied to clipboard."
                                            }
                                        },
                                        onShareTxt = {
                                            val text = onBuildTextReport()
                                            if (text != null) {
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_SUBJECT, "Redmi 4X Hardware Lab Diagnostic Report")
                                                    putExtra(Intent.EXTRA_TEXT, text)
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Report"))
                                            }
                                        },
                                        buildPreviewText = onBuildTextReport,
                                        statusBanner = exportStatusMessage
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
