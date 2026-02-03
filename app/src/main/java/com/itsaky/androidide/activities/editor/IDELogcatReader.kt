/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.activities.editor

import android.os.Process
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads the logs from AndroidIDE and saves it to a file in the projects directory.
 *
 * @author Akash Yadav
 */
class IDELogcatReader {

  private var job: Job? = null
  private var shouldRun = false

  companion object {

    private val log = LoggerFactory.getLogger(IDELogcatReader::class.java)
    private const val MAX_LOG_BYTES: Long = 5L * 1024L * 1024L
  }

  /**
   * Start reading the logs.
   */
  fun start() {
    shouldRun = true

    check(job == null) {
      "Logcat reader is already running"
    }

    job = CoroutineScope(Dispatchers.IO).launch {
      run()
    }
  }

  /**
   * Stop the log reader.
   */
  fun stop() {
    shouldRun = false
    job?.cancel("User requested cancellation")
    job = null
  }

  private fun run() {
    val date = Date()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss.SSS", Locale.US)
    val outputFile = File(Environment.ANDROIDIDE_HOME,
      "logs/AndroidIDE-LOG-${dateFormat.format(date)}.txt")

    log.debug("Creating output file: {}", outputFile)

    outputFile.parentFile!!.mkdirs()
    try {
      outputFile.createNewFile()
    } catch (e: Exception) {
      log.error("Failed to create output file for log", e)
      return
    }

    outputFile.outputStream().buffered().use { writer ->
      try {
        val captureAllBuffers = GeneralPreferences.logcatCaptureAll
        val args = mutableListOf("logcat")
        if (captureAllBuffers) {
          args.addAll(listOf("-b", "all"))
        }
        args.addAll(listOf("--pid", Process.myPid().toString(), "-v", "threadtime"))

        val process = ProcessBuilder(args).let { builder ->
          builder.redirectErrorStream(true)
          builder.start()
        }

        val buffer = ByteArray(8 * 1024)
        var written = 0L
        process.inputStream.use { input ->
          while (shouldRun) {
            val read = input.read(buffer)
            if (read <= 0) break
            val remaining = MAX_LOG_BYTES - written
            if (remaining <= 0) break
            val toWrite = if (read > remaining) remaining.toInt() else read
            writer.write(buffer, 0, toWrite)
            written += toWrite
            if (written >= MAX_LOG_BYTES) break
          }
        }

        writer.flush()
        if (written >= MAX_LOG_BYTES) {
          log.warn("Logcat capture reached max size ({} bytes). Truncating output.", MAX_LOG_BYTES)
        }

        process.destroy()
        log.info("Process ended with exit code: {}", process.waitFor())
      } catch (err: Throwable) {
        log.error("Failed to read logs", err)
      }
    }
  }
}
