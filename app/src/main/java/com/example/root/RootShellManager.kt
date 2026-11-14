package com.example.root

import com.example.domain.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

data class RootProbeResult(
    val status: RootStatus,
    val detail: String,
    val magiskVersion: String?
)

data class RootCommandResult(
    val success: Boolean,
    val output: String?,
    val error: String?
)

/**
 * Strictly READ-ONLY root access manager.
 * - Probes actual root execution via `su -c id` (never assumes file existence = root granted).
 * - Enforces an explicit allowlist of read-only paths and commands.
 * - Rejects any write, redirect, pipe, or traversal attempt.
 * - Applies strict process timeouts, stream draining (no pipe deadlock), and output bounds.
 */
object RootCommandAllowlist {

    private val forbiddenTokens = listOf(";", "|", "&", ">", "<", "$", "`", "..", "\n", "\r", "\\", "\"", "'")

    private val allowedExactCommands = setOf(
        "id",
        "getenforce",
        "uname -a",
        "magisk -v",
        "magisk -V"
    )

    private val allowedPathPrefixes = listOf(
        "/sys/class/power_supply/",
        "/sys/class/thermal/",
        "/sys/devices/system/cpu/",
        "/sys/class/kgsl/",
        "/sys/class/devfreq/",
        "/sys/block/",
        "/sys/fs/selinux/enforce",
        "/sys/module/workqueue/",
        "/sys/kernel/fast_charge/",
        "/sys/android_touch/",
        "/proc/cpuinfo",
        "/proc/stat",
        "/proc/meminfo",
        "/proc/loadavg",
        "/proc/mounts",
        "/proc/partitions",
        "/proc/version",
        "/proc/sys/vm/",
        "/proc/sys/kernel/",
        "/proc/sys/net/ipv4/tcp_congestion_control",
        "/vendor/etc/thermal-engine",
        "/system/etc/thermal-engine"
    )

    private val allowedListDirs = listOf(
        "/sys/class/power_supply",
        "/sys/class/thermal",
        "/sys/devices/system/cpu",
        "/sys/class/kgsl",
        "/sys/class/devfreq",
        "/sys/block"
    )

    fun isSafePath(path: String): Boolean {
        if (path.isBlank() || path.length > 256) return false
        if (forbiddenTokens.any { path.contains(it) }) return false
        return allowedPathPrefixes.any { prefix -> path.startsWith(prefix) }
    }

    fun isSafeListDir(path: String): Boolean {
        if (path.isBlank() || path.length > 256) return false
        if (forbiddenTokens.any { path.contains(it) }) return false
        val normalized = path.trimEnd('/')
        return allowedListDirs.any { dir ->
            normalized == dir || normalized.startsWith("$dir/")
        }
    }

    fun isAllowedCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isBlank() || trimmed.length > 300) return false
        if (forbiddenTokens.any { trimmed.contains(it) }) return false
        if (trimmed in allowedExactCommands) return true
        if (trimmed.startsWith("cat ")) {
            val target = trimmed.removePrefix("cat ").trim()
            return isSafePath(target)
        }
        if (trimmed.startsWith("ls ")) {
            val target = trimmed.removePrefix("ls ").trim()
            return isSafeListDir(target)
        }
        return false
    }
}

class RootShellManager {

    @Volatile
    var cachedStatus: RootStatus? = null
        private set

    @Volatile
    var cachedProbeDetail: String = "Pending root verification..."
        private set

    @Volatile
    var cachedMagiskVersion: String? = null
        private set

    private val missingRootPaths: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val persistentShellLock = Any()
    private var persistentProcess: Process? = null
    private var persistentWriter: java.io.BufferedWriter? = null
    private var persistentReader: java.io.BufferedReader? = null

    suspend fun probeRootAccess(forceRefresh: Boolean = false): RootProbeResult = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedStatus != null) {
            return@withContext RootProbeResult(
                status = cachedStatus!!,
                detail = cachedProbeDetail,
                magiskVersion = cachedMagiskVersion
            )
        }

        if (forceRefresh) {
            missingRootPaths.clear()
            closePersistentShell()
        }

        val probeExec = executeRawSu("id", timeoutMs = 4000L)
        val status: RootStatus
        val detail: String

        when {
            probeExec.binaryMissing -> {
                status = RootStatus.ROOT_NOT_FOUND
                detail = "su binary not found or not executable on device."
            }
            probeExec.timedOut -> {
                status = RootStatus.ROOT_DENIED
                detail = "su -c id timed out (root prompt dismissed or hung)."
            }
            probeExec.exitCode == 0 && probeExec.output.contains("uid=0") -> {
                status = RootStatus.ROOT_AVAILABLE
                detail = probeExec.output.trim().ifBlank { "uid=0(root)" }
            }
            else -> {
                status = RootStatus.ROOT_DENIED
                val msg = probeExec.output.trim()
                detail = if (msg.isNotBlank()) "su denied (${probeExec.exitCode}): $msg" else "su exited with code ${probeExec.exitCode}"
            }
        }

        val magiskVersion = if (status == RootStatus.ROOT_AVAILABLE) {
            detectMagiskVersion()
        } else {
            detectMagiskNonRootArtifact()
        }

        cachedStatus = status
        cachedProbeDetail = detail
        cachedMagiskVersion = magiskVersion

        RootProbeResult(status = status, detail = detail, magiskVersion = magiskVersion)
    }

    suspend fun readAllowlistedFile(path: String): RootCommandResult = withContext(Dispatchers.IO) {
        if (cachedStatus != RootStatus.ROOT_AVAILABLE) {
            return@withContext RootCommandResult(false, null, "Root not available")
        }
        if (path in missingRootPaths) {
            return@withContext RootCommandResult(false, null, "Cached missing path")
        }
        if (!RootCommandAllowlist.isSafePath(path)) {
            return@withContext RootCommandResult(false, null, "Path blocked by read-only allowlist")
        }
        val res = executeAllowlisted("cat $path")
        if (!res.success || res.output.isNullOrBlank()) {
            missingRootPaths.add(path)
        }
        res
    }

    /**
     * Reads multiple allowlisted sysfs/procfs files in a persistent read-only `su` session
     * (falling back to single `su -c` calls if needed) to eliminate per-tick spawn latency.
     */
    suspend fun readAllowlistedBatch(paths: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (cachedStatus != RootStatus.ROOT_AVAILABLE || paths.isEmpty()) {
            return@withContext emptyMap()
        }
        val safePaths = paths
            .distinct()
            .filter { it !in missingRootPaths && RootCommandAllowlist.isSafePath(it) }

        if (safePaths.isEmpty()) return@withContext emptyMap()

        val results = LinkedHashMap<String, String>()
        // Chunk to keep command line short and bounded
        safePaths.chunked(35).forEach { chunk ->
            val script = chunk.joinToString("; ") { p ->
                "if [ -r '$p' ]; then echo '__HWLAB_BEGIN__$p'; head -c 4096 '$p' 2>/dev/null; echo ''; echo '__HWLAB_END__'; fi"
            }
            val persistentOut = executeInPersistentShell(script, timeoutMs = 2000L)
            val rawOutput = if (persistentOut != null) {
                persistentOut
            } else {
                val execRes = executeRawSu(script, timeoutMs = 2500L)
                if (execRes.exitCode == 0 && !execRes.timedOut) execRes.output else ""
            }
            if (rawOutput.isNotBlank()) {
                val parsed = parseBatchOutput(rawOutput)
                results.putAll(parsed)
                chunk.forEach { p ->
                    if (p !in parsed) {
                        missingRootPaths.add(p)
                    }
                }
            }
        }
        results
    }

    private fun executeInPersistentShell(script: String, timeoutMs: Long): String? = synchronized(persistentShellLock) {
        if (cachedStatus != RootStatus.ROOT_AVAILABLE) return null
        val sentinel = "__HWLAB_BATCH_DONE_${System.nanoTime()}__"
        val future = java.util.concurrent.FutureTask {
            val writer = ensurePersistentShellWriter() ?: return@FutureTask null
            val reader = persistentReader ?: return@FutureTask null
            writer.write(script)
            writer.write("; echo '$sentinel'\n")
            writer.flush()

            val sb = StringBuilder()
            var totalChars = 0
            while (totalChars < 131072) {
                val line = reader.readLine() ?: return@FutureTask null
                if (line == sentinel) {
                    return@FutureTask sb.toString()
                }
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
                totalChars += line.length + 1
            }
            sb.toString()
        }

        val worker = Thread(future, "hwlab-su-shell").apply {
            isDaemon = true
            start()
        }
        return try {
            val output = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (output == null) {
                closePersistentShellLocked()
            }
            output
        } catch (_: Exception) {
            future.cancel(true)
            closePersistentShellLocked()
            null
        } finally {
            try {
                worker.join(50)
            } catch (_: Exception) {
            }
        }
    }

    private fun ensurePersistentShellWriter(): java.io.BufferedWriter? {
        val existing = persistentProcess
        val existingAlive = existing != null && try {
            existing.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
        if (existingAlive && persistentWriter != null && persistentReader != null) {
            return persistentWriter
        }
        closePersistentShellLocked()
        return try {
            val proc = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            persistentProcess = proc
            persistentWriter = proc.outputStream.bufferedWriter()
            persistentReader = proc.inputStream.bufferedReader()
            persistentWriter
        } catch (_: Exception) {
            closePersistentShellLocked()
            null
        }
    }

    fun closePersistentShell() = synchronized(persistentShellLock) {
        closePersistentShellLocked()
    }

    private fun closePersistentShellLocked() {
        try {
            persistentWriter?.close()
        } catch (_: Exception) {
        }
        try {
            persistentReader?.close()
        } catch (_: Exception) {
        }
        try {
            persistentProcess?.destroyForcibly()
        } catch (_: Exception) {
        }
        persistentWriter = null
        persistentReader = null
        persistentProcess = null
    }

    private fun parseBatchOutput(raw: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var currentPath: String? = null
        val buffer = StringBuilder()

        raw.lineSequence().forEach { line ->
            when {
                line.startsWith("__HWLAB_BEGIN__") -> {
                    currentPath = line.removePrefix("__HWLAB_BEGIN__").trim()
                    buffer.clear()
                }
                line == "__HWLAB_END__" -> {
                    val path = currentPath
                    if (path != null) {
                        val content = buffer.toString().trim()
                        if (content.isNotEmpty()) {
                            map[path] = content
                        }
                    }
                    currentPath = null
                    buffer.clear()
                }
                currentPath != null -> {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(line)
                }
            }
        }
        return map
    }

    suspend fun listAllowlistedDir(path: String): List<String>? = withContext(Dispatchers.IO) {
        if (cachedStatus != RootStatus.ROOT_AVAILABLE) return@withContext null
        if (!RootCommandAllowlist.isSafeListDir(path)) return@withContext null
        val res = executeAllowlisted("ls $path")
        if (!res.success || res.output.isNullOrBlank()) return@withContext null
        res.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    suspend fun executeAllowlisted(command: String): RootCommandResult = withContext(Dispatchers.IO) {
        if (!RootCommandAllowlist.isAllowedCommand(command)) {
            return@withContext RootCommandResult(false, null, "Command blocked by read-only allowlist")
        }
        val res = executeRawSu(command.trim(), timeoutMs = 2000L)
        if (res.exitCode == 0 && !res.timedOut && !res.binaryMissing) {
            RootCommandResult(true, res.output.trimEnd(), null)
        } else {
            RootCommandResult(false, null, res.output.ifBlank { "Exit ${res.exitCode}" })
        }
    }

    private fun detectMagiskVersion(): String? {
        val ver = executeRawSu("magisk -v; magisk -V", timeoutMs = 1500L)
        if (ver.exitCode != 0 || ver.timedOut) return null
        val lines = ver.output.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        return when {
            lines.size >= 2 -> "${lines[0]} (${lines[1]})"
            lines.size == 1 -> lines[0]
            else -> null
        }
    }

    private fun detectMagiskNonRootArtifact(): String? {
        val markers = listOf("/sbin/.magisk", "/data/adb/magisk", "/system/bin/magisk")
        val found = markers.firstOrNull { File(it).exists() }
        return found?.let { "Marker detected ($it) - root access required for version" }
    }

    private data class RawExecResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
        val binaryMissing: Boolean
    )

    private fun executeRawSu(command: String, timeoutMs: Long): RawExecResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val outputHolder = StringBuilder()
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        val buf = CharArray(4096)
                        var total = 0
                        while (total < 65536) {
                            val n = reader.read(buf)
                            if (n <= 0) break
                            outputHolder.appendRange(buf, 0, n)
                            total += n
                        }
                    }
                } catch (_: Exception) {
                }
            }
            readerThread.isDaemon = true
            readerThread.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(150)
                return RawExecResult(-1, outputHolder.toString(), timedOut = true, binaryMissing = false)
            }
            readerThread.join(300)
            RawExecResult(process.exitValue(), outputHolder.toString(), timedOut = false, binaryMissing = false)
        } catch (e: java.io.IOException) {
            RawExecResult(-1, e.message ?: "IO error", timedOut = false, binaryMissing = true)
        } catch (e: Exception) {
            RawExecResult(-1, e.message ?: "Execution failed", timedOut = false, binaryMissing = false)
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
        }
    }
}
