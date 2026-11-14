package com.example.utils

import android.content.Context
import com.example.domain.StorageBenchmarkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Bounded, strictly non-destructive storage I/O test.
 * Writes a temporary 4 MB test file inside app cacheDir, fsyncs, reads sequentially,
 * and deletes the temporary file immediately in a finally block.
 */
object StorageBenchmark {

    suspend fun runBoundedIoTest(context: Context, payloadMb: Int = 4): StorageBenchmarkResult =
        withContext(Dispatchers.IO) {
            val safeMb = payloadMb.coerceIn(1, 8)
            val totalBytes = safeMb * 1024 * 1024
            val buffer = ByteArray(64 * 1024) { (it and 0xFF).toByte() }
            val tempFile = File(context.cacheDir, "hwlab_io_bench.tmp")

            try {
                if (tempFile.exists()) tempFile.delete()

                val writeStartNs = System.nanoTime()
                FileOutputStream(tempFile).use { fos ->
                    var written = 0
                    while (written < totalBytes) {
                        val toWrite = minOf(buffer.size, totalBytes - written)
                        fos.write(buffer, 0, toWrite)
                        written += toWrite
                    }
                    fos.flush()
                    fos.fd.sync()
                }
                val writeDurationSec = ((System.nanoTime() - writeStartNs) / 1_000_000_000.0).coerceAtLeast(0.001)

                val readStartNs = System.nanoTime()
                FileInputStream(tempFile).use { fis ->
                    val readBuf = ByteArray(64 * 1024)
                    while (fis.read(readBuf) != -1) {
                        // Sequential read pass
                    }
                }
                val readDurationSec = ((System.nanoTime() - readStartNs) / 1_000_000_000.0).coerceAtLeast(0.001)
                val totalDurationMs = ((writeDurationSec + readDurationSec) * 1000.0).toLong()

                StorageBenchmarkResult(
                    timestampMs = System.currentTimeMillis(),
                    payloadMb = safeMb,
                    writeSpeedMbPerSec = safeMb / writeDurationSec,
                    readSpeedMbPerSec = safeMb / readDurationSec,
                    durationMs = totalDurationMs
                )
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
}
