package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.monitoring.HardwareLabViewModel
import com.example.ui.HardwareLabApp
import com.example.ui.theme.HardwareLabTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HardwareLabViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            HardwareLabTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> viewModel.onForegroundChanged(true)
                            Lifecycle.Event.ON_STOP -> viewModel.onForegroundChanged(false)
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                HardwareLabApp(
                    state = uiState,
                    onSelectPage = viewModel::selectPage,
                    onSelectInterval = viewModel::setRefreshInterval,
                    onTogglePause = viewModel::togglePause,
                    onResetStats = viewModel::resetStatistics,
                    onToggleBackground = viewModel::setBackgroundMonitoring,
                    onReProbeRoot = viewModel::reProbeRoot,
                    onRunBenchmark = viewModel::runStorageBenchmark,
                    onToggleSensorLiveStream = viewModel::setSensorLiveStream,
                    onRunDiagnostics = { viewModel.runDiagnostics() },
                    onBuildTextReport = viewModel::buildTextReport,
                    onBuildCsvReport = viewModel::buildCsvReport
                )
            }
        }
    }
}
