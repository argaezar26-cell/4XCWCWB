package com.example.data

import android.os.Build
import com.example.domain.KernelSystemInfo
import com.example.domain.RootStatus
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
        forceRootProbe: Boolean = false
    ): KernelSystemInfo = withContext(Dispatchers.IO) {
        val rootProbe = rootShellManager.probeRootAccess(forceRefresh = forceRootProbe)

        val procVersion = sysfsReader.readText("/proc/version").content
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

    private suspend fun readSelinuxStatus(rootStatus: RootStatus): String {
        val direct = sysfsReader.readDirect("/sys/fs/selinux/enforce")
        when (direct) {
            "1" -> return "Enforcing"
            "0" -> return "Permissive"
        }
        if (rootStatus == RootStatus.ROOT_AVAILABLE) {
            val res = rootShellManager.executeAllowlisted("getenforce")
            if (res.success && !res.output.isNullOrBlank()) {
                return res.output.trim()
            }
        }
        return "Restricted / Unavailable"
    }

    private suspend fun readThermalConfigPreview(rootStatus: RootStatus): String? {
        val candidates = listOf(
            "/vendor/etc/thermal-engine.conf",
            "/vendor/etc/thermal-engine-8940.conf",
            "/system/etc/thermal-engine.conf"
        )
        for (path in candidates) {
            val res = sysfsReader.readText(path, allowRootFallback = (rootStatus == RootStatus.ROOT_AVAILABLE))
            if (!res.content.isNullOrBlank()) {
                val lines = res.content.lineSequence().take(18).joinToString("\n")
                return "[$path]\n$lines"
            }
        }
        return null
    }
}
