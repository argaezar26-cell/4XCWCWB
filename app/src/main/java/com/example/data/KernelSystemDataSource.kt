package com.example.data

import android.os.Build
import com.example.domain.KernelSystemInfo
import com.example.domain.RootStatus
import com.example.root.RootProbeResult
import com.example.root.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KernelSystemDataSource(
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
            thermalConfigPreview = thermalConfig
        )
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
