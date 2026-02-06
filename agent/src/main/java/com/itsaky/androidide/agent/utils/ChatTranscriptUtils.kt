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

package com.itsaky.androidide.agent.utils

import android.content.Context
import com.itsaky.androidide.utils.FileShareUtils
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatTranscriptUtils {
    @Throws(IOException::class)
    fun writeTranscriptToCache(context: Context, transcript: String): File {
        val exportsDir = File(context.cacheDir, "chat_exports")
        if (exportsDir.exists()) {
            if (!exportsDir.isDirectory) {
                throw IOException("Exports path is not a directory: ${exportsDir.path}")
            }
        } else if (!exportsDir.mkdirs()) {
            throw IOException("Failed to create exports directory: ${exportsDir.path}")
        }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US).format(Date())
        val file = File(exportsDir, "chat-transcript-$timestamp.txt")
        file.writeText(transcript, Charsets.UTF_8)
        return file
    }

    @Throws(IOException::class)
    fun shareTranscript(context: Context, transcript: String) {
        val file = writeTranscriptToCache(context, transcript)
        FileShareUtils.shareFile(context, file, "text/plain")
    }
}
