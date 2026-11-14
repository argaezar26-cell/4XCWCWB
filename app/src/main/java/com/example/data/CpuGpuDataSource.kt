package com.example.data

import android.app.ActivityManager
import android.content.Context
import com.example.domain.CpuCoreTelemetry
import com.example.domain.CpuFreqParser
import com.example.domain.CpuTelemetry
import com.example.domain.DataSourceOrigin
import com.example.domain.GpuTelemetry
import com.example.domain.ThermalParser
import com.example.domain.UnitConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL10

class CpuGpuDataSource(
    context: Context,
    private val sysfsReader: SysfsReader
) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    @Volatile
    private var previousProcStat: Map<String, CpuFreqParser.ProcStatSample> = emptyMap()

    @Volatile
    private var cachedGlRenderer: Pair<String?, String?>? = null

    suspend fun readCpuTelemetry(): CpuTelemetry = withContext(Dispatchers.IO) {
        val cpuInfoRes = sysfsReader.readText("/proc/cpuinfo")
        val (modelName, hardwarePlatform, architecture) = CpuFreqParser.parseCpuInfo(cpuInfoRes.content)

        val statRes = sysfsReader.readText("/proc/stat")
        val currProcStat = CpuFreqParser.parseProcStat(statRes.content)
        val totalUtil = CpuFreqParser.computeUtilizationPercent(previousProcStat["cpu"], currProcStat["cpu"])

        val loadAvgRes = sysfsReader.readText("/proc/loadavg")
        val loadTokens = loadAvgRes.content?.trim()?.split(Regex("\\s+")) ?: emptyList()
        val load1 = loadTokens.getOrNull(0)?.toDoubleOrNull()
        val load5 = loadTokens.getOrNull(1)?.toDoubleOrNull()
        val load15 = loadTokens.getOrNull(2)?.toDoubleOrNull()

        val cpuBaseDir = "/sys/devices/system/cpu"
        val (dirEntries, _) = sysfsReader.listDirectory(cpuBaseDir, allowRootFallback = false)
        val discoveredIndices = dirEntries
            .filter { it.matches(Regex("^cpu\\d+$")) }
            .mapNotNull { it.removePrefix("cpu").toIntOrNull() }
            .sorted()

        val totalCores = if (discoveredIndices.isNotEmpty()) {
            discoveredIndices.size
        } else {
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        }
        val coreIndices = if (discoveredIndices.isNotEmpty()) discoveredIndices else (0 until totalCores).toList()

        val allGovernors = LinkedHashSet<String>()
        val allFrequencies = LinkedHashSet<Double>()
        var anyRootUsed = cpuInfoRes.usedRoot || statRes.usedRoot

        val coreList = coreIndices.map { index ->
            val corePath = "$cpuBaseDir/cpu$index"
            val onlineVal = if (index == 0) {
                true
            } else {
                val (onlineRaw, r) = sysfsReader.readLong("$corePath/online")
                if (r) anyRootUsed = true
                onlineRaw?.let { it == 1L }
            }

            val cpufreqPath = "$corePath/cpufreq"
            var coreUsedRoot = false

            suspend fun readFreq(file: String): Double? {
                val (raw, usedRoot) = sysfsReader.readLong("$cpufreqPath/$file")
                if (usedRoot) {
                    coreUsedRoot = true
                    anyRootUsed = true
                }
                return raw?.let { UnitConverter.frequencyToMhz(it)?.value }
            }

            val curFreq = readFreq("scaling_cur_freq")
            val minFreq = readFreq("scaling_min_freq")
            val maxFreq = readFreq("scaling_max_freq")
            val hwMaxFreq = readFreq("cpuinfo_max_freq")

            val govRes = sysfsReader.readText("$cpufreqPath/scaling_governor")
            if (govRes.usedRoot) {
                coreUsedRoot = true
                anyRootUsed = true
            }

            val availGovRes = sysfsReader.readText("$cpufreqPath/scaling_available_governors")
            if (availGovRes.usedRoot) anyRootUsed = true
            allGovernors.addAll(CpuFreqParser.parseSpaceSeparatedTokens(availGovRes.content))

            val availFreqRes = sysfsReader.readText("$cpufreqPath/scaling_available_frequencies")
            if (availFreqRes.usedRoot) anyRootUsed = true
            allFrequencies.addAll(CpuFreqParser.parseFrequencyListMhz(availFreqRes.content))

            val coreKey = "cpu$index"
            val coreUtil = CpuFreqParser.computeUtilizationPercent(
                previousProcStat[coreKey],
                currProcStat[coreKey]
            )

            CpuCoreTelemetry(
                coreIndex = index,
                isOnline = onlineVal,
                currentFreqMhz = curFreq,
                minFreqMhz = minFreq,
                maxFreqMhz = maxFreq,
                hardwareMaxFreqMhz = hwMaxFreq,
                governor = govRes.content?.takeIf { it.isNotBlank() },
                utilizationPercent = coreUtil,
                source = sysfsReader.resolveSource(DataSourceOrigin.SYSFS_CPU, coreUsedRoot)
            )
        }

        previousProcStat = currProcStat
        val onlineCores = coreList.count { it.isOnline == true || it.currentFreqMhz != null }

        CpuTelemetry(
            modelName = modelName,
            hardwarePlatform = hardwarePlatform,
            architecture = architecture,
            totalCores = totalCores,
            onlineCores = onlineCores.coerceAtLeast(1),
            totalUtilizationPercent = totalUtil,
            loadAvg1m = load1,
            loadAvg5m = load5,
            loadAvg15m = load15,
            cores = coreList,
            availableGovernors = allGovernors.toList(),
            availableFrequenciesMhz = allFrequencies.sorted(),
            source = sysfsReader.resolveSource(DataSourceOrigin.PROCFS, anyRootUsed)
        )
    }

    suspend fun readGpuTelemetry(): GpuTelemetry = withContext(Dispatchers.IO) {
        val (glRenderer, glVendor) = getGlInfo()
        val glEsVersion = activityManager?.deviceConfigurationInfo?.glEsVersion

        // Primary probe: Qualcomm Adreno KGSL node
        val kgslPath = "/sys/class/kgsl/kgsl-3d0"
        val (kgslClockRaw, kgslRoot1) = sysfsReader.readLong("$kgslPath/gpuclk")
        val (kgslMaxRaw, kgslRoot2) = sysfsReader.readLong("$kgslPath/max_gpuclk")
        val (kgslMinRaw, kgslRoot3) = sysfsReader.readLong("$kgslPath/min_gpuclk")
        val kgslBusyRes = sysfsReader.readText("$kgslPath/gpubusy")
        val kgslModelRes = sysfsReader.readText("$kgslPath/gpu_model")
        val kgslAvailRes = sysfsReader.readText("$kgslPath/gpu_available_frequencies")
        val (kgslTempRaw, kgslTempRoot) = sysfsReader.readLong("$kgslPath/temp")
        val kgslGovRes = sysfsReader.readText("$kgslPath/devfreq/governor")

        val hasKgsl = kgslClockRaw != null || kgslMaxRaw != null || kgslBusyRes.content != null || kgslModelRes.content != null

        if (hasKgsl) {
            val usedRoot = kgslRoot1 || kgslRoot2 || kgslRoot3 || kgslBusyRes.usedRoot ||
                kgslModelRes.usedRoot || kgslAvailRes.usedRoot || kgslTempRoot || kgslGovRes.usedRoot

            val curMhz = kgslClockRaw?.let { UnitConverter.frequencyToMhz(it)?.value }
            val maxMhz = kgslMaxRaw?.let { UnitConverter.frequencyToMhz(it)?.value }
            val minMhz = kgslMinRaw?.let { UnitConverter.frequencyToMhz(it)?.value }
            val util = parseKgslBusy(kgslBusyRes.content)
            val tempC = kgslTempRaw?.let { ThermalParser.parseTemperatureCelsius(it)?.celsius }
            val availMhz = CpuFreqParser.parseFrequencyListMhz(kgslAvailRes.content)
            val name = glRenderer ?: kgslModelRes.content?.takeIf { it.isNotBlank() } ?: "Qualcomm Adreno (KGSL)"

            return@withContext GpuTelemetry(
                gpuName = name,
                vendor = glVendor ?: "Qualcomm",
                glEsVersion = glEsVersion,
                currentFreqMhz = curMhz,
                maxFreqMhz = maxMhz,
                minFreqMhz = minMhz,
                utilizationPercent = util,
                governor = kgslGovRes.content?.takeIf { it.isNotBlank() },
                temperatureC = tempC,
                availableFrequenciesMhz = availMhz,
                sysfsNodePath = kgslPath,
                source = sysfsReader.resolveSource(DataSourceOrigin.SYSFS_KGSL, usedRoot),
                isSysfsAvailable = true
            )
        }

        // Fallback: /sys/class/devfreq/* GPU/kgsl/adreno/mali nodes
        val devfreqBase = "/sys/class/devfreq"
        val (devfreqEntries, devDirRoot) = sysfsReader.listDirectory(devfreqBase)
        val gpuNodeName = devfreqEntries.firstOrNull { name ->
            val lower = name.lowercase()
            lower.contains("kgsl") || lower.contains("gpu") || lower.contains("adreno") || lower.contains("mali")
        }

        if (gpuNodeName != null) {
            val nodePath = "$devfreqBase/$gpuNodeName"
            val (curRaw, r1) = sysfsReader.readLong("$nodePath/cur_freq")
            val (maxRaw, r2) = sysfsReader.readLong("$nodePath/max_freq")
            val (minRaw, r3) = sysfsReader.readLong("$nodePath/min_freq")
            val govRes = sysfsReader.readText("$nodePath/governor")
            val availRes = sysfsReader.readText("$nodePath/available_frequencies")
            val usedRoot = devDirRoot || r1 || r2 || r3 || govRes.usedRoot || availRes.usedRoot

            return@withContext GpuTelemetry(
                gpuName = glRenderer ?: gpuNodeName,
                vendor = glVendor,
                glEsVersion = glEsVersion,
                currentFreqMhz = curRaw?.let { UnitConverter.frequencyToMhz(it)?.value },
                maxFreqMhz = maxRaw?.let { UnitConverter.frequencyToMhz(it)?.value },
                minFreqMhz = minRaw?.let { UnitConverter.frequencyToMhz(it)?.value },
                utilizationPercent = null,
                governor = govRes.content?.takeIf { it.isNotBlank() },
                temperatureC = null,
                availableFrequenciesMhz = CpuFreqParser.parseFrequencyListMhz(availRes.content),
                sysfsNodePath = nodePath,
                source = sysfsReader.resolveSource(DataSourceOrigin.SYSFS_DEVFREQ, usedRoot),
                isSysfsAvailable = true
            )
        }

        // OpenGL ES driver fallback only (never invent sysfs clock frequencies)
        GpuTelemetry(
            gpuName = glRenderer ?: "Unavailable",
            vendor = glVendor,
            glEsVersion = glEsVersion,
            currentFreqMhz = null,
            maxFreqMhz = null,
            minFreqMhz = null,
            utilizationPercent = null,
            governor = null,
            temperatureC = null,
            availableFrequenciesMhz = emptyList(),
            sysfsNodePath = null,
            source = if (glRenderer != null) DataSourceOrigin.EGL_DRIVER else DataSourceOrigin.UNAVAILABLE,
            isSysfsAvailable = false
        )
    }

    private fun parseKgslBusy(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (parts.size < 2) return null
        val busy = parts[0]
        val total = parts[1]
        if (total <= 0L || busy < 0L) return null
        return ((busy.toDouble() / total.toDouble()) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun getGlInfo(): Pair<String?, String?> {
        cachedGlRenderer?.let { return it }
        val result = try {
            val egl = EGLContext.getEGL() as? EGL10 ?: return null to null
            val display: EGLDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            if (display == EGL10.EGL_NO_DISPLAY) return null to null
            val version = IntArray(2)
            if (!egl.eglInitialize(display, version)) return null to null

            val configSpec = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 4,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!egl.eglChooseConfig(display, configSpec, configs, 1, numConfig) || numConfig[0] <= 0) {
                egl.eglTerminate(display)
                return null to null
            }
            val config = configs[0] ?: run {
                egl.eglTerminate(display)
                return null to null
            }
            val contextAttr = intArrayOf(0x3098, 2, EGL10.EGL_NONE)
            val context: EGLContext = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttr)
            val surfaceAttr = intArrayOf(EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE)
            val surface: EGLSurface = egl.eglCreatePbufferSurface(display, config, surfaceAttr)
            var renderer: String? = null
            var vendor: String? = null
            if (context != EGL10.EGL_NO_CONTEXT && surface != EGL10.EGL_NO_SURFACE) {
                if (egl.eglMakeCurrent(display, surface, surface, context)) {
                    val gl = context.gl as? GL10
                    renderer = gl?.glGetString(GL10.GL_RENDERER)?.takeIf { it.isNotBlank() }
                    vendor = gl?.glGetString(GL10.GL_VENDOR)?.takeIf { it.isNotBlank() }
                    egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                }
            }
            if (surface != EGL10.EGL_NO_SURFACE) egl.eglDestroySurface(display, surface)
            if (context != EGL10.EGL_NO_CONTEXT) egl.eglDestroyContext(display, context)
            egl.eglTerminate(display)
            renderer to vendor
        } catch (_: Throwable) {
            null to null
        }
        cachedGlRenderer = result
        return result
    }
}
