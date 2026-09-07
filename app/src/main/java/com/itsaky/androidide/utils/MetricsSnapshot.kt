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
import androidx.annotation.VisibleForTesting
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

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

	/**
	 * How many snapshots to keep.
	 *
	 * Enough that a share still has its file when the recipient gets round to reading it, few
	 * enough that a long session cannot fill the cache. These are a few hundred kilobytes each.
	 */
	@VisibleForTesting
	internal const val KEEP_RECENT = 5

	/** Media type for the written file, for the sharing intent. */
	const val MIME_TYPE = "image/png"

	/**
	 * Writes [bitmap] as a PNG, named by [MetricsFileName] like every other exported metrics file.
	 *
	 * The name used to lead with the chart's title. ADFA-5531 made one naming rule for the image and
	 * the CSV so that a pair exported together sorts together, and a title in front of the timestamp
	 * would have sorted them apart.
	 *
	 * A few recent snapshots are kept rather than only the newest. This is a scratch directory for
	 * handing an image to another app, not a gallery, so it stays bounded -- but a share hands the
	 * recipient a FileProvider URI and the chooser returns long before the recipient opens it.
	 * Deleting the previous file on the next export therefore pulled an image out from under an
	 * app that had not read it yet. [KEEP_RECENT] is the slack that buys.
	 *
	 * @return the file, or `null` if it could not be written.
	 */
	fun write(
		context: Context,
		bitmap: Bitmap,
		nowMillis: Long = System.currentTimeMillis(),
	): File? {
		val directory = File(context.cacheDir, DIRECTORY)
		return try {
			if (!directory.exists() && !directory.mkdirs()) {
				log.error("Could not create the snapshot directory at {}", directory)
				return null
			}

			val file = File(directory, MetricsFileName.forTime(nowMillis, "png"))
			file.outputStream().use { output ->
				if (!bitmap.compress(Bitmap.CompressFormat.PNG, QUALITY, output)) {
					log.error("Could not encode the chart snapshot")
					return null
				}
			}
			pruneTo(directory, KEEP_RECENT, file)
			file
		} catch (io: IOException) {
			log.error("Could not write the chart snapshot", io)
			null
		}
	}

	/**
	 * Trims [directory] to the [limit] most recent snapshots, always keeping [newest].
	 *
	 * Oldest first, by last-modified. The file just written is protected explicitly rather than
	 * trusted to sort newest: two exports in the same second share a timestamp, and the filename
	 * carries only whole seconds.
	 */
	internal fun pruneTo(
		directory: File,
		limit: Int,
		newest: File,
	) {
		val files = directory.listFiles()?.sortedBy { it.lastModified() } ?: return
		if (files.size <= limit) {
			return
		}
		files.take(files.size - limit).forEach { file ->
			if (file != newest && !file.delete()) {
				log.warn("Could not delete the stale chart snapshot at {}", file)
			}
		}
	}
}
