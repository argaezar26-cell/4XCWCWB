package com.example.root

import com.example.domain.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
 * - Applies strict process timeouts and output bounds.
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
        "/proc/cpuinfo",
        "/proc/stat",
        "/proc/meminfo",
        "/proc/loadavg",
        "/proc/mounts",
        "/proc/partitions",
        "/proc/version",
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
    var cachedProbeDetail: String = "Not probed yet"
        private set

    @Volatile
    var cachedMagiskVersion: String? = null
        private set

    suspend fun probeRootAccess(forceRefresh: Boolean = false): RootProbeResult = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedStatus != null) {
            return@withContext RootProbeResult(
                status = cachedStatus!!,
                detail = cachedProbeDetail,
                magiskVersion = cachedMagiskVersion
            )
        }

        val probeExec = executeRawSu("id", timeoutMs = 2000L)
        val status: RootStatus
        val detail: String

        when {
            probeExec.binaryMissing -> {
                status = RootStatus.ROOT_NOT_FOUND
                detail = "su binary not found or not executable on device."
            }
            probeExec.timedOut -> {
                status = RootStatus.ROOT_DENIED
                detail = "su -c id timed out after 2000ms (root prompt dismissed or hung)."
            }
            probeExec.exitCode == 0 && probeExec.stdout.contains("uid=0") -> {
                status = RootStatus.ROOT_AVAILABLE
                detail = probeExec.stdout.trim().ifBlank { "uid=0(root)" }
            }
            else -> {
                status = RootStatus.ROOT_DENIED
                val msg = probeExec.stderr.ifBlank { probeExec.stdout }.trim()
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
        if (!RootCommandAllowlist.isSafePath(path)) {
            return@withContext RootCommandResult(false, null, "Path blocked by read-only allowlist")
        }
        executeAllowlisted("cat $path")
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
            RootCommandResult(true, res.stdout.trimEnd(), null)
        } else {
            RootCommandResult(false, null, res.stderr.ifBlank { "Exit ${res.exitCode}" })
        }
    }

    private fun detectMagiskVersion(): String? {
        val ver = executeRawSu("magisk -v", timeoutMs = 1500L)
        val code = executeRawSu("magisk -V", timeoutMs = 1500L)
        val verStr = if (ver.exitCode == 0) ver.stdout.trim() else ""
        val codeStr = if (code.exitCode == 0) code.stdout.trim() else ""
        return when {
            verStr.isNotBlank() && codeStr.isNotBlank() -> "$verStr ($codeStr)"
            verStr.isNotBlank() -> verStr
            else -> null
        }
    }

    private fun detectMagiskNonRootArtifact(): String? {
        // Non-root read-only check of Magisk app/environment markers without claiming root access
        val markers = listOf("/sbin/.magisk", "/data/adb/magisk", "/system/bin/magisk")
        val found = markers.firstOrNull { File(it).exists() }
        return found?.let { "Marker detected ($it) - root access required for version" }
    }

    private data class RawExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val binaryMissing: Boolean
    )

    private fun executeRawSu(command: String, timeoutMs: Long): RawExecResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command).start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return RawExecResult(-1, "", "Timeout", timedOut = true, binaryMissing = false)
            }
            val maxBytes = 64 * 1024
            val stdout = process.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(maxBytes)
                val read = reader.read(buf)
                if (read > 0) String(buf, 0, read) else ""
            }
            val stderr = process.errorStream.bufferedReader().use { reader ->
                val buf = CharArray(8192)
                val read = reader.read(buf)
                if (read > 0) String(buf, 0, read) else ""
            }
            RawExecResult(process.exitValue(), stdout, stderr, timedOut = false, binaryMissing = false)
        } catch (e: java.io.IOException) {
            RawExecResult(-1, "", e.message ?: "IO error", timedOut = false, binaryMissing = true)
        } catch (e: Exception) {
            RawExecResult(-1, "", e.message ?: "Execution failed", timedOut = false, binaryMissing = false)
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
        }
    }
}
