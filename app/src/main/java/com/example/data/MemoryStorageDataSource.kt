package com.example.data

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.example.domain.BlockDeviceInfo
import com.example.domain.CpuFreqParser
import com.example.domain.DataSourceOrigin
import com.example.domain.MemoryTelemetry
import com.example.domain.MountPointInfo
import com.example.domain.StorageBenchmarkResult
import com.example.domain.StorageTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MemoryStorageDataSource(
    context: Context,
    private val sysfsReader: SysfsReader
) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    suspend fun readMemoryTelemetry(): MemoryTelemetry = withContext(Dispatchers.IO) {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)

        val procMemRes = sysfsReader.readText("/proc/meminfo")
        val procMap = CpuFreqParser.parseMemInfo(procMemRes.content)

        val totalBytes = procMap["MemTotal"] ?: memInfo.totalMem
        val availBytes = procMap["MemAvailable"] ?: memInfo.availMem
        val usedBytes = (totalBytes - availBytes).coerceAtLeast(0L)
        val freeBytes = procMap["MemFree"]
        val cachedBytes = procMap["Cached"]
        val buffersBytes = procMap["Buffers"]
        val swapTotalBytes = procMap["SwapTotal"]
        val swapFreeBytes = procMap["SwapFree"]

        val utilPercent = if (totalBytes > 0L) {
            ((usedBytes.toDouble() / totalBytes.toDouble()) * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        val sources = buildList {
            if (activityManager != null) add(DataSourceOrigin.ACTIVITY_MANAGER)
            if (procMap.isNotEmpty()) add(sysfsReader.resolveSource(DataSourceOrigin.PROCFS, procMemRes.usedRoot))
        }.ifEmpty { listOf(DataSourceOrigin.UNAVAILABLE) }

        val interpretation = buildMemoryInterpretation(
            utilPercent = utilPercent,
            isLowMemory = memInfo.lowMemory,
            cachedBytes = cachedBytes,
            totalBytes = totalBytes,
            swapTotalBytes = swapTotalBytes,
            swapFreeBytes = swapFreeBytes
        )

        MemoryTelemetry(
            totalRamBytes = totalBytes,
            availableRamBytes = availBytes,
            usedRamBytes = usedBytes,
            freeRamBytes = freeBytes,
            cachedRamBytes = cachedBytes,
            buffersBytes = buffersBytes,
            swapTotalBytes = swapTotalBytes,
            swapFreeBytes = swapFreeBytes,
            utilizationPercent = utilPercent,
            isLowMemory = memInfo.lowMemory,
            thresholdBytes = memInfo.threshold,
            interpretation = interpretation,
            sources = sources
        )
    }

    suspend fun readStorageTelemetry(lastBenchmark: StorageBenchmarkResult?): StorageTelemetry =
        withContext(Dispatchers.IO) {
            val dataDir = Environment.getDataDirectory()
            val statFs = StatFs(dataDir.path)
            val totalBytes = statFs.totalBytes
            val freeBytes = statFs.availableBytes
            val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)
            val usagePct = if (totalBytes > 0L) {
                ((usedBytes.toDouble() / totalBytes.toDouble()) * 100.0).coerceIn(0.0, 100.0)
            } else {
                0.0
            }

            val mountsRes = sysfsReader.readText("/proc/mounts")
            val mounts = parseMounts(mountsRes.content)
            val primaryMount = mounts.firstOrNull { it.mountPoint == "/data" }
                ?: mounts.firstOrNull { it.mountPoint == "/" }

            val blockDevices = scanBlockDevices()
            val diagnosticNote = buildStorageDiagnostic(usagePct, primaryMount?.fsType)

            StorageTelemetry(
                internalTotalBytes = totalBytes,
                internalUsedBytes = usedBytes,
                internalFreeBytes = freeBytes,
                usagePercent = usagePct,
                primaryFsType = primaryMount?.fsType,
                mounts = mounts.take(24),
                blockDevices = blockDevices,
                diagnosticNote = diagnosticNote,
                lastBenchmark = lastBenchmark
            )
        }

    private fun parseMounts(raw: String?): List<MountPointInfo> {
        if (raw.isNullOrBlank()) return emptyList()
        val interestingTypes = setOf("ext4", "f2fs", "erofs", "squashfs", "vfat", "exfat", "sdcardfs", "fuse")
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4) {
                    val fs = parts[2]
                    val mp = parts[1]
                    if (fs in interestingTypes || mp == "/" || mp == "/data" || mp == "/system" || mp == "/vendor") {
                        MountPointInfo(
                            device = parts[0],
                            mountPoint = mp,
                            fsType = fs,
                            options = parts[3]
                        )
                    } else null
                } else null
            }
            .toList()
    }

    private suspend fun scanBlockDevices(): List<BlockDeviceInfo> {
        val baseDir = "/sys/block"
        val (entries, dirUsedRoot) = sysfsReader.listDirectory(baseDir)
        if (entries.isEmpty()) return emptyList()

        val filtered = entries.filter {
            it.startsWith("mmcblk") || it.startsWith("sd") || it.startsWith("zram") || it.startsWith("dm-")
        }.take(12)

        val blockFiles = listOf("size", "queue/rotational", "queue/scheduler")
        val batch = sysfsReader.preloadBatchForRoot(
            filtered.flatMap { name -> blockFiles.map { f -> "$baseDir/$name/$f" } }
        )

        return filtered.map { name ->
            val path = "$baseDir/$name"
            val r1 = batch["$path/size"]
            val r2 = batch["$path/queue/rotational"]
            val schedRes = batch["$path/queue/scheduler"]
            val usedRoot = dirUsedRoot || r1?.usedRoot == true || r2?.usedRoot == true || schedRes?.usedRoot == true

            val sectors = r1?.content?.trim()?.toLongOrNull()
            val rotRaw = r2?.content?.trim()?.toLongOrNull()

            BlockDeviceInfo(
                name = name,
                sizeBytes = sectors?.let { it * 512L },
                rotational = rotRaw?.let { it != 0L },
                scheduler = schedRes?.content,
                source = sysfsReader.resolveSource(DataSourceOrigin.SYSFS_BLOCK, usedRoot)
            )
        }
    }

    private fun buildMemoryInterpretation(
        utilPercent: Double,
        isLowMemory: Boolean,
        cachedBytes: Long?,
        totalBytes: Long,
        swapTotalBytes: Long?,
        swapFreeBytes: Long?
    ): String {
        val cachePct = if (cachedBytes != null && totalBytes > 0L) (cachedBytes * 100.0 / totalBytes) else null
        val swapUsedPct = if (swapTotalBytes != null && swapTotalBytes > 0L && swapFreeBytes != null) {
            ((swapTotalBytes - swapFreeBytes) * 100.0 / swapTotalBytes).coerceIn(0.0, 100.0)
        } else null

        val base = when {
            isLowMemory || utilPercent >= 90.0 -> "High RAM pressure — Android LMKD may begin reclaiming background processes."
            utilPercent >= 75.0 -> "Moderate-to-high RAM usage; active apps and kernel page cache fill most physical RAM."
            else -> "Healthy RAM headroom; Android keeps recently used apps cached for fast resume."
        }
        val extra = buildList {
            if (cachePct != null) add("Page cache holds ${String.format(java.util.Locale.US, "%.0f", cachePct)}% of total RAM (reclaimable).")
            if (swapUsedPct != null) add("zRAM/Swap utilization at ${String.format(java.util.Locale.US, "%.0f", swapUsedPct)}%.")
        }.joinToString(" ")
        return if (extra.isNotEmpty()) "$base $extra" else base
    }

    private fun buildStorageDiagnostic(usagePct: Double, fsType: String?): String {
        val fsNote = if (fsType != null) "Primary /data filesystem: $fsType. " else ""
        val spaceNote = when {
            usagePct >= 92.0 -> "Free space critically low (>92% used) — may degrade flash write amplification and OS updates."
            usagePct >= 80.0 -> "Free space moderately constrained (>80% used)."
            else -> "Capacity headroom is nominal."
        }
        return "${fsNote}${spaceNote} Note: Free capacity alone does not measure eMMC/NAND flash wear or bad-block health."
    }
}
