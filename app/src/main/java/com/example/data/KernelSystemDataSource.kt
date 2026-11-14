package com.example.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.example.domain.DataSourceOrigin
import com.example.domain.DisplayTelemetry
import com.example.domain.KernelSystemInfo
import com.example.domain.KernelTunableItem
import com.example.domain.RootStatus
import com.example.root.RootProbeResult
import com.example.root.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KernelSystemDataSource(
    private val context: Context,
    private val sysfsReader: SysfsReader,
    private val rootShellManager: RootShellManager
) {

    suspend fun readKernelSystemInfo(
        availableGovernors: List<String>,
        availableFrequenciesMhz: List<Double>,
        allowRootProbe: Boolean = false,
        forceRootProbe: Boolean = false
    ): KernelSystemInfo = withContext(Dispatchers.IO) {
        val rootProbe = when {
            forceRootProbe || (allowRootProbe && rootShellManager.cachedStatus == null) -> {
                rootShellManager.probeRootAccess(forceRefresh = forceRootProbe)
            }
            rootShellManager.cachedStatus != null -> {
                RootProbeResult(
                    status = rootShellManager.cachedStatus!!,
                    detail = rootShellManager.cachedProbeDetail,
                    magiskVersion = rootShellManager.cachedMagiskVersion
                )
            }
            else -> {
                RootProbeResult(
                    status = RootStatus.ROOT_NOT_FOUND,
                    detail = "Checking root access...",
                    magiskVersion = null
                )
            }
        }

        val procVersion = sysfsReader.readText("/proc/version", allowRootFallback = false).content
        val osVersion = System.getProperty("os.version") ?: "Unavailable"

        val selinuxStatus = readSelinuxStatus(rootProbe.status)
        val thermalConfig = readThermalConfigPreview(rootProbe.status)
        val tunables = readKernelTunables()
        val displayInfo = readDisplayTelemetry()

        val securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH.ifBlank { "Unavailable" }
        } else {
            "Unavailable"
        }

        KernelSystemInfo(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            manufacturer = Build.MANUFACTURER,
            deviceCodename = Build.DEVICE,
            board = Build.BOARD,
            hardware = Build.HARDWARE,
            androidVersion = Build.VERSION.RELEASE ?: "Unknown",
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = securityPatch,
            buildFingerprint = Build.FINGERPRINT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            kernelVersion = osVersion,
            linuxProcVersion = procVersion,
            selinuxStatus = selinuxStatus,
            rootStatus = rootProbe.status,
            rootProbeDetail = rootProbe.detail,
            magiskVersion = rootProbe.magiskVersion,
            availableGovernors = availableGovernors,
            availableCpuFrequenciesMhz = availableFrequenciesMhz,
            thermalConfigPreview = thermalConfig,
            tunables = tunables,
            displayInfo = displayInfo
        )
    }

    private suspend fun readKernelTunables(): List<KernelTunableItem> {
        val specs = listOf(
            "vm.swappiness" to "/proc/sys/vm/swappiness",
            "vm.vfs_cache_pressure" to "/proc/sys/vm/vfs_cache_pressure",
            "vm.dirty_ratio" to "/proc/sys/vm/dirty_ratio",
            "TCP Congestion" to "/proc/sys/net/ipv4/tcp_congestion_control",
            "sched_boost" to "/proc/sys/kernel/sched_boost",
            "Workqueue Power Efficient" to "/sys/module/workqueue/parameters/power_efficient",
            "USB Fast Charge Flag" to "/sys/kernel/fast_charge/force_fast_charge"
        )
        val batch = sysfsReader.preloadBatchForRoot(specs.map { it.second })
        return specs.map { (label, path) ->
            val res = batch[path]
            val value = res?.content?.trim()?.takeIf { it.isNotBlank() }
            val source = if (value != null) {
                sysfsReader.resolveSource(
                    if (path.startsWith("/proc/")) DataSourceOrigin.PROCFS else DataSourceOrigin.SYSFS_POWER_SUPPLY,
                    res?.usedRoot == true
                )
            } else {
                DataSourceOrigin.UNAVAILABLE
            }
            KernelTunableItem(
                label = label,
                path = path,
                value = value,
                source = source
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun readDisplayTelemetry(): DisplayTelemetry? = try {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display: Display? = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        val metrics = DisplayMetrics()
        display?.getRealMetrics(metrics)
        val widthPx = metrics.widthPixels.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val heightPx = metrics.heightPixels.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val densityDpi = metrics.densityDpi.takeIf { it > 0 } ?: context.resources.displayMetrics.densityDpi
        val refreshHz = display?.refreshRate ?: 60f
        val supportedRates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && display != null) {
            display.supportedModes.map { it.refreshRate }.distinct().sorted()
        } else {
            listOf(refreshHz)
        }
        val isHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && display != null) {
            display.isHdr
        } else {
            false
        }
        DisplayTelemetry(
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = densityDpi,
            refreshRateHz = refreshHz,
            supportedRefreshRatesHz = supportedRates,
            isHdrCapable = isHdr
        )
    } catch (_: Exception) {
        null
    }

    @Volatile
    private var cachedSelinuxStatus: String? = null

    private suspend fun readSelinuxStatus(rootStatus: RootStatus): String {
        val direct = sysfsReader.readDirect("/sys/fs/selinux/enforce")
        when (direct) {
            "1" -> return "Enforcing"
            "0" -> return "Permissive"
        }
        cachedSelinuxStatus?.let { return it }
        if (rootStatus == RootStatus.ROOT_AVAILABLE) {
            val res = rootShellManager.executeAllowlisted("getenforce")
            if (res.success && !res.output.isNullOrBlank()) {
                val status = res.output.trim()
                cachedSelinuxStatus = status
                return status
            }
        }
        return "Restricted / Unavailable"
    }

    @Volatile
    private var cachedThermalConfig: String? = null
    @Volatile
    private var thermalConfigProbed: Boolean = false

    private suspend fun readThermalConfigPreview(rootStatus: RootStatus): String? {
        if (thermalConfigProbed && cachedThermalConfig != null) return cachedThermalConfig
        val candidates = listOf(
            "/vendor/etc/thermal-engine.conf",
            "/vendor/etc/thermal-engine-8940.conf",
            "/system/etc/thermal-engine.conf"
        )
        val batch = if (rootStatus == RootStatus.ROOT_AVAILABLE) {
            thermalConfigProbed = true
            sysfsReader.preloadBatchForRoot(candidates)
        } else {
            emptyMap()
        }
        for (path in candidates) {
            val content = sysfsReader.readDirect(path) ?: batch[path]?.content
            if (!content.isNullOrBlank()) {
                val lines = content.lineSequence().take(18).joinToString("\n")
                val preview = "[$path]\n$lines"
                cachedThermalConfig = preview
                return preview
            }
        }
        return null
    }
}
