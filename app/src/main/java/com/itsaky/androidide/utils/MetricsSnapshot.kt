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

package com.itsaky.androidide.utils

import android.content.Context
import android.graphics.Bitmap
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a metrics chart image to a file the IDE can share (ADFA-5486).
 *
 * Snapshots go to a directory under the cache, so the platform can reclaim them and they never
 * accumulate; the sharing intent gives the receiving app a grant on the file before that matters.
 */
object MetricsSnapshot {
	private val log = LoggerFactory.getLogger(MetricsSnapshot::class.java)

	private const val DIRECTORY = "metrics-snapshots"
	private const val QUALITY = 100
	private const val TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss"

	/** Media type for the written file, for the sharing intent. */
	const val MIME_TYPE = "image/png"

	/**
	 * Writes [bitmap] as a PNG named after [label] and the current time.
	 *
	 * Old snapshots are cleared first: this is a scratch directory for handing one image to another
	 * app, not a gallery, and an IDE session could otherwise leave a pile of them behind.
	 *
	 * @return the file, or `null` if it could not be written.
	 */
	fun write(
		context: Context,
		bitmap: Bitmap,
		label: String,
	): File? {
		val directory = File(context.cacheDir, DIRECTORY)
		return try {
			if (directory.exists()) {
				directory.listFiles()?.forEach { it.delete() }
			} else if (!directory.mkdirs()) {
				log.error("Could not create the snapshot directory at {}", directory)
				return null
			}

			val file = File(directory, "${fileNameFor(label)}.png")
			file.outputStream().use { output ->
				if (!bitmap.compress(Bitmap.CompressFormat.PNG, QUALITY, output)) {
					log.error("Could not encode the chart snapshot")
					return null
				}
			}
			file
		} catch (io: IOException) {
			log.error("Could not write the chart snapshot", io)
			null
		}
	}

	/**
	 * A filename from [label] and the current time, with anything that is not safe in a filename
	 * replaced. Chart titles are translated, so they can contain spaces and non-ASCII.
	 */
	private fun fileNameFor(label: String): String {
		val stamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date())
		val safeLabel =
			label
				.lowercase(Locale.US)
				.replace(Regex("[^a-z0-9]+"), "-")
				.trim('-')
				.ifEmpty { "metrics" }
		return "$safeLabel-$stamp"
	}
}
