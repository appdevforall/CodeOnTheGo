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

package com.itsaky.androidide.plugins.tasks

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream
import com.aayushatharva.brotli4j.encoder.Encoder
import java.io.File

/**
 * Adds the provided file to assets in brotli compressed form.
 */
abstract class AddBrotliFileToAssetsTask : AddFileToAssetsTask() {
    override fun doCopy(inFile: File, outFile: File) {
        val brotliOut = File(outFile.path + ".br")
        Brotli4jLoader.ensureAvailability()

        val start = System.nanoTime()
        val params = Encoder.Parameters()
            .setQuality(11)
            .setWindow(24)

        BrotliOutputStream(brotliOut.outputStream(), params).use { out ->
            inFile.inputStream().use { input ->
                input.copyTo(out)
            }
        }

        val end = System.nanoTime()
        val durationMs = (end - start) / (1_000_000)
        project.logger.lifecycle("brotli time: ${durationMs}ms")
    }
}