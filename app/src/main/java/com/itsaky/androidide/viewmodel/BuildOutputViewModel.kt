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
package com.itsaky.androidide.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File-backed build output with a moving window in memory. All output is appended to a session
 * file; the UI can request the tail (for initial/restore view) or any range (for scroll). Full
 * content is read from file on demand for share/API. Memory is bounded by not holding the full
 * log in RAM.
 *
 * Append/clear are intended to be called from the main thread (from [BuildOutputFragment]).
 */
class BuildOutputViewModel(application: Application) : AndroidViewModel(application) {

  private val lock = ReentrantLock()
  private val sessionFile: File
    get() = File(getApplication<Application>().cacheDir, SESSION_FILE_NAME)

  /**
   * Appends text to the session file. File I/O is performed on a background dispatcher; call from
   * any thread. Prefer calling before switching to Main so disk write does not block the UI.
   */
  suspend fun append(text: String) {
    if (text.isEmpty()) return
    withContext(Dispatchers.IO) {
      lock.withLock {
        try {
          FileOutputStream(sessionFile, true).use {
            it.write(text.toByteArray(StandardCharsets.UTF_8))
          }
        } catch (e: Exception) {
          log.error("Failed to append build output to session file", e)
        }
      }
    }
  }

  /**
   * Returns the last [WINDOW_SIZE_CHARS] characters from the session file for the editor to
   * display (e.g. initial view or after rotation). Returns empty string if no content.
   */
  fun getWindowForEditor(): String =
    lock.withLock {
      readTailFromFile(sessionFile, WINDOW_SIZE_CHARS)
    }

  /**
   * Returns the full build output from the session file. Used for [BuildOutputProvider.getBuildOutputContent]
   * and share/copy. Returns empty string if no content. File I/O is performed on [Dispatchers.IO].
   */
  suspend fun getFullContent(): String =
    withContext(Dispatchers.IO) {
      lock.withLock {
        if (!sessionFile.exists()) return@withContext ""
        try {
          sessionFile.readText()
        } catch (e: Exception) {
          log.error("Failed to read full build output from session file", e)
          ""
        }
      }
    }

  /**
   * Reads a range from the session file (for future scroll/windowed UI). [offset] and [length] are
   * in characters; implementation reads the corresponding byte range and decodes.
   */
  fun getRange(offset: Int, length: Int): String =
    lock.withLock {
      if (!sessionFile.exists()) return ""
      try {
        val content = sessionFile.readText()
        val start = max(0, offset).coerceAtMost(content.length)
        val end = (start + length).coerceAtMost(content.length)
        content.substring(start, end)
      } catch (e: Exception) {
        log.error("Failed to read range from build output session file", e)
        ""
      }
    }

  /**
   * Clears the session: deletes the session file and resets state. Call when a new build starts.
   */
  fun clear() {
    lock.withLock {
      try {
        if (sessionFile.exists()) {
          sessionFile.delete()
        }
      } catch (e: Exception) {
        log.error("Failed to delete build output session file", e)
      }
    }
  }

  private fun readTailFromFile(file: File, maxChars: Int): String {
    if (!file.exists()) return ""
    try {
      RandomAccessFile(file, "r").use { raf ->
        val len = raf.length()
        if (len == 0L) return ""
        // UTF-8: up to 4 bytes per char; read enough bytes for maxChars, then decode and take last maxChars
        val maxBytes = minOf(len, maxChars * 4L)
        raf.seek(max(0, len - maxBytes))
        val bytes = ByteArray(maxBytes.toInt())
        raf.readFully(bytes)
        val decoded = String(bytes, Charsets.UTF_8)
        return if (decoded.length <= maxChars) decoded else decoded.takeLast(maxChars)
      }
    } catch (e: Exception) {
      log.error("Failed to read tail from build output session file", e)
      return ""
    }
  }

  companion object {
    private const val SESSION_FILE_NAME = "build_output_session.txt"
    private const val WINDOW_SIZE_CHARS = 512 * 1024
    private val log = org.slf4j.LoggerFactory.getLogger(BuildOutputViewModel::class.java)
  }
}
