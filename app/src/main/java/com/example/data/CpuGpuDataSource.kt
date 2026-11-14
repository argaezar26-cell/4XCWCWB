package com.example.data

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import com.example.domain.CpuCoreTelemetry
import com.example.domain.CpuFreqParser
import com.example.domain.CpuTelemetry
import com.example.domain.CpuTimeInStateCluster
import com.example.domain.DataSourceOrigin
import com.example.domain.GpuTelemetry
import com.example.domain.ThermalParser
import com.example.domain.UnitConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        val procBatch = sysfsReader.preloadBatchForRoot(
            listOf("/proc/cpuinfo", "/proc/stat", "/proc/loadavg")
        )
        val cpuInfoRes = procBatch["/proc/cpuinfo"]
        val (modelName, hardwarePlatform, architecture) = CpuFreqParser.parseCpuInfo(cpuInfoRes?.content)

        val statRes = procBatch["/proc/stat"]
        val currProcStat = CpuFreqParser.parseProcStat(statRes?.content)
        val totalUtil = CpuFreqParser.computeUtilizationPercent(previousProcStat["cpu"], currProcStat["cpu"])

        val loadAvgRes = procBatch["/proc/loadavg"]
        val loadTokens = loadAvgRes?.content?.trim()?.split(Regex("\\s+")) ?: emptyList()
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

        val cpuFiles = listOf(
            "cpufreq/scaling_cur_freq",
            "cpufreq/scaling_min_freq",
            "cpufreq/scaling_max_freq",
            "cpufreq/cpuinfo_max_freq",
            "cpufreq/scaling_governor",
            "cpufreq/scaling_available_governors",
            "cpufreq/scaling_available_frequencies"
        )
        val timeInStateCandidates = listOf(
            "$cpuBaseDir/cpu0/cpufreq/stats/time_in_state",
            "$cpuBaseDir/cpufreq/policy0/stats/time_in_state",
            "$cpuBaseDir/cpu4/cpufreq/stats/time_in_state",
            "$cpuBaseDir/cpufreq/policy4/stats/time_in_state"
        )

        val cpuPaths = buildList {
            coreIndices.forEach { idx ->
                if (idx > 0) add("$cpuBaseDir/cpu$idx/online")
                cpuFiles.forEach { f -> add("$cpuBaseDir/cpu$idx/$f") }
            }
            addAll(timeInStateCandidates)
        }
        val cpuBatch = sysfsReader.preloadBatchForRoot(cpuPaths)

        val allGovernors = LinkedHashSet<String>()
        val allFrequencies = LinkedHashSet<Double>()
        var anyRootUsed = cpuInfoRes?.usedRoot == true || statRes?.usedRoot == true

        val coreList = coreIndices.map { index ->
            val corePath = "$cpuBaseDir/cpu$index"
            val onlineVal = if (index == 0) {
                true
            } else {
                val res = cpuBatch["$corePath/online"]
                if (res?.usedRoot == true) anyRootUsed = true
                res?.content?.trim()?.toLongOrNull()?.let { it == 1L }
            }

            val cpufreqPath = "$corePath/cpufreq"
            var coreUsedRoot = false

            fun readFreq(file: String): Double? {
                val res = cpuBatch["$cpufreqPath/$file"] ?: return null
                if (res.usedRoot) {
                    coreUsedRoot = true
                    anyRootUsed = true
                }
                return res.content?.trim()?.toLongOrNull()?.let { UnitConverter.frequencyToMhz(it)?.value }
            }

            val curFreq = readFreq("scaling_cur_freq")
            val minFreq = readFreq("scaling_min_freq")
            val maxFreq = readFreq("scaling_max_freq")
            val hwMaxFreq = readFreq("cpuinfo_max_freq")

            val govRes = cpuBatch["$cpufreqPath/scaling_governor"]
            if (govRes?.usedRoot == true) {
                coreUsedRoot = true
                anyRootUsed = true
            }

            val availGovRes = cpuBatch["$cpufreqPath/scaling_available_governors"]
            if (availGovRes?.usedRoot == true) anyRootUsed = true
            allGovernors.addAll(CpuFreqParser.parseSpaceSeparatedTokens(availGovRes?.content))

            val availFreqRes = cpuBatch["$cpufreqPath/scaling_available_frequencies"]
            if (availFreqRes?.usedRoot == true) anyRootUsed = true
            allFrequencies.addAll(CpuFreqParser.parseFrequencyListMhz(availFreqRes?.content))

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
                governor = govRes?.content?.takeIf { it.isNotBlank() },
                utilizationPercent = coreUtil,
                source = sysfsReader.resolveSource(DataSourceOrigin.SYSFS_CPU, coreUsedRoot)
            )
        }

        previousProcStat = currProcStat
        val onlineCores = coreList.count { it.isOnline == true || it.currentFreqMhz != null }

        val totalElapsedMs = SystemClock.elapsedRealtime().coerceAtLeast(0L)
        val awakeUptimeMs = SystemClock.uptimeMillis().coerceAtLeast(0L)
        val deepSleepMs = (totalElapsedMs - awakeUptimeMs).coerceAtLeast(0L)
        val deepSleepPct = if (totalElapsedMs > 0L) {
            (deepSleepMs.toDouble() / totalElapsedMs.toDouble()) * 100.0
        } else 0.0

        val clusters = buildList {
            val littlePath = listOf(
                "$cpuBaseDir/cpu0/cpufreq/stats/time_in_state",
                "$cpuBaseDir/cpufreq/policy0/stats/time_in_state"
            ).firstOrNull { !cpuBatch[it]?.content.isNullOrBlank() }
            if (littlePath != null) {
                val res = cpuBatch[littlePath]
                if (res?.usedRoot == true) anyRootUsed = true
                val entries = CpuFreqParser.parseTimeInState(res?.content)
                if (entries.isNotEmpty()) {
                    add(CpuTimeInStateCluster("Cluster 0 (Little / Core 0-3)", littlePath, entries))
                }
            }

            val bigPath = listOf(
                "$cpuBaseDir/cpu4/cpufreq/stats/time_in_state",
                "$cpuBaseDir/cpufreq/policy4/stats/time_in_state"
            ).firstOrNull { !cpuBatch[it]?.content.isNullOrBlank() }
            if (bigPath != null) {
                val res = cpuBatch[bigPath]
                if (res?.usedRoot == true) anyRootUsed = true
                val entries = CpuFreqParser.parseTimeInState(res?.content)
                if (entries.isNotEmpty()) {
                    add(CpuTimeInStateCluster("Cluster 1 (Big / Core 4-7)", bigPath, entries))
                }
            }
        }

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
            source = sysfsReader.resolveSource(DataSourceOrigin.PROCFS, anyRootUsed),
            totalElapsedMs = totalElapsedMs,
            awakeUptimeMs = awakeUptimeMs,
            deepSleepMs = deepSleepMs,
            deepSleepPercent = deepSleepPct,
            timeInStateClusters = clusters
        )
    }

    suspend fun readGpuTelemetry(): GpuTelemetry = withContext(Dispatchers.IO) {
        val (glRenderer, glVendor) = withTimeoutOrNull(800L) { getGlInfo() } ?: (null to null)
        val glEsVersion = activityManager?.deviceConfigurationInfo?.glEsVersion

        // Primary probe: Qualcomm Adreno KGSL node (batched)
        val kgslPath = "/sys/class/kgsl/kgsl-3d0"
        val kgslFiles = listOf(
            "gpuclk", "max_gpuclk", "min_gpuclk", "gpubusy",
            "gpu_model", "gpu_available_frequencies", "temp", "devfreq/governor"
        )
        val kgslBatch = sysfsReader.preloadBatchForRoot(kgslFiles.map { "$kgslPath/$it" })

        val kgslClockRaw = kgslBatch["$kgslPath/gpuclk"]?.content?.toLongOrNull()
        val kgslMaxRaw = kgslBatch["$kgslPath/max_gpuclk"]?.content?.toLongOrNull()
        val kgslMinRaw = kgslBatch["$kgslPath/min_gpuclk"]?.content?.toLongOrNull()
        val kgslBusyRes = kgslBatch["$kgslPath/gpubusy"]
        val kgslModelRes = kgslBatch["$kgslPath/gpu_model"]
        val kgslAvailRes = kgslBatch["$kgslPath/gpu_available_frequencies"]
        val kgslTempRaw = kgslBatch["$kgslPath/temp"]?.content?.toLongOrNull()
        val kgslGovRes = kgslBatch["$kgslPath/devfreq/governor"]

        val hasKgsl = kgslClockRaw != null || kgslMaxRaw != null || kgslBusyRes?.content != null || kgslModelRes?.content != null

        if (hasKgsl) {
            val usedRoot = kgslBatch.values.any { it.usedRoot }
            val curMhz = kgslClockRaw?.let { UnitConverter.frequencyToMhz(it)?.value }
            val maxMhz = kgslMaxRaw?.let { UnitConverter.frequencyToMhz(it)?.value }
            val minMhz = kgslMinRaw?.let { UnitConverter.frequencyToMhz(it)?.value }
            val util = parseKgslBusy(kgslBusyRes?.content)
            val tempC = kgslTempRaw?.let { ThermalParser.parseTemperatureCelsius(it)?.celsius }
            val availMhz = CpuFreqParser.parseFrequencyListMhz(kgslAvailRes?.content)
            val name = glRenderer ?: kgslModelRes?.content?.takeIf { it.isNotBlank() } ?: "Qualcomm Adreno (KGSL)"

            return@withContext GpuTelemetry(
                gpuName = name,
                vendor = glVendor ?: "Qualcomm",
                glEsVersion = glEsVersion,
                currentFreqMhz = curMhz,
                maxFreqMhz = maxMhz,
                minFreqMhz = minMhz,
                utilizationPercent = util,
                governor = kgslGovRes?.content?.takeIf { it.isNotBlank() },
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
            val devBatch = sysfsReader.preloadBatchForRoot(
                listOf("cur_freq", "max_freq", "min_freq", "governor", "available_frequencies")
                    .map { "$nodePath/$it" }
            )
            val curRaw = devBatch["$nodePath/cur_freq"]?.content?.toLongOrNull()
            val maxRaw = devBatch["$nodePath/max_freq"]?.content?.toLongOrNull()
            val minRaw = devBatch["$nodePath/min_freq"]?.content?.toLongOrNull()
            val govRes = devBatch["$nodePath/governor"]
            val availRes = devBatch["$nodePath/available_frequencies"]
            val usedRoot = devDirRoot || devBatch.values.any { it.usedRoot }

            return@withContext GpuTelemetry(
                gpuName = glRenderer ?: gpuNodeName,
                vendor = glVendor,
                glEsVersion = glEsVersion,
                currentFreqMhz = curRaw?.let { UnitConverter.frequencyToMhz(it)?.value },
                maxFreqMhz = maxRaw?.let { UnitConverter.frequencyToMhz(it)?.value },
                minFreqMhz = minRaw?.let { UnitConverter.frequencyToMhz(it)?.value },
                utilizationPercent = null,
                governor = govRes?.content?.takeIf { it.isNotBlank() },
                temperatureC = null,
                availableFrequenciesMhz = CpuFreqParser.parseFrequencyListMhz(availRes?.content),
                sysfsNodePath = nodePath,
                source = sysfsReader.resolveSource(DataSourceOrigin.SYSFS_DEVFREQ, usedRoot),
                isSysfsAvailable = true
            )
        }

        fallbackGpuTelemetry()
    }

    fun fallbackGpuTelemetry(): GpuTelemetry {
        val glEsVersion = activityManager?.deviceConfigurationInfo?.glEsVersion
        val (glRenderer, glVendor) = cachedGlRenderer ?: (null to null)
        return GpuTelemetry(
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
