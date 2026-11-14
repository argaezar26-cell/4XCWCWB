package com.example.data

import com.example.domain.DataSourceOrigin
import com.example.domain.RootStatus
import com.example.root.RootShellManager
import java.io.File

data class ReadResult(
    val content: String?,
    val usedRoot: Boolean
)

/**
 * Safely reads Linux sysfs and procfs nodes via direct File I/O first,
 * falling back to read-only allowlisted root (`RootShellManager`) only if
 * root is already verified as ROOT_AVAILABLE and direct read fails due to SELinux/permissions.
 */
class SysfsReader(
    private val rootShellManager: RootShellManager
) {

    fun readDirect(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return null
            file.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                var total = 0
                while (total < 65536) {
                    val count = reader.read(buf)
                    if (count <= 0) break
                    sb.appendRange(buf, 0, count)
                    total += count
                }
                sb.toString().trim()
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun readText(path: String, allowRootFallback: Boolean = true): ReadResult {
        val direct = readDirect(path)
        if (direct != null) {
            return ReadResult(direct, usedRoot = false)
        }
        if (allowRootFallback && rootShellManager.cachedStatus == RootStatus.ROOT_AVAILABLE) {
            val rootRes = rootShellManager.readAllowlistedFile(path)
            if (rootRes.success && !rootRes.output.isNullOrBlank()) {
                return ReadResult(rootRes.output.trim(), usedRoot = true)
            }
        }
        return ReadResult(null, usedRoot = false)
    }

    suspend fun readLong(path: String, allowRootFallback: Boolean = true): Pair<Long?, Boolean> {
        val res = readText(path, allowRootFallback)
        val parsed = res.content?.trim()?.toLongOrNull()
        return parsed to res.usedRoot
    }

    suspend fun listDirectory(dirPath: String, allowRootFallback: Boolean = true): Pair<List<String>, Boolean> {
        try {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) {
                val entries = dir.list()?.toList()
                if (!entries.isNullOrEmpty()) {
                    return entries.sorted() to false
                }
            }
        } catch (_: Exception) {
        }
        if (allowRootFallback && rootShellManager.cachedStatus == RootStatus.ROOT_AVAILABLE) {
            val rootEntries = rootShellManager.listAllowlistedDir(dirPath)
            if (!rootEntries.isNullOrEmpty()) {
                return rootEntries.sorted() to true
            }
        }
        return emptyList<String>() to false
    }

    fun resolveSource(defaultSource: DataSourceOrigin, usedRoot: Boolean): DataSourceOrigin {
        return if (usedRoot) DataSourceOrigin.ROOT_KERNEL_INTERFACE else defaultSource
    }
}
