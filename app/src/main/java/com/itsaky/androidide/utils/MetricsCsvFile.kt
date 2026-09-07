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
import androidx.annotation.VisibleForTesting
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.zip.GZIPOutputStream

/**
 * Writes a [MetricsCsv.Snapshot] to a file the IDE can share (ADFA-5531).
 *
 * The same scratch-directory arrangement as [MetricsSnapshot], and for the same reason: exports go
 * under the cache so the platform can reclaim them, and the sharing intent grants the recipient a
 * read on the file before that matters.
 */
object MetricsCsvFile {
	private val log = LoggerFactory.getLogger(MetricsCsvFile::class.java)

	private const val DIRECTORY = "metrics-exports"

	/**
	 * Where a file written for a report goes, rather than for the user.
	 *
	 * Separate from the exports because they prune independently: a feedback send must not evict an
	 * export the user is about to hand to another app (ADFA-5534).
	 */
	private const val REPORT_DIRECTORY = "metrics-reports"

	/** Gzip, not zip: one file, so an archive container adds a name and nothing else. */
	private const val COMPRESSED_EXTENSION = "csv.gz"

	/** Media type for a compressed file. */
	const val COMPRESSED_MIME_TYPE = "application/gzip"

	/**
	 * How many exports to keep.
	 *
	 * A share hands the recipient a URI and returns long before the recipient reads it, so the
	 * previous file cannot be deleted on the next export. Fewer than the images are kept: a full
	 * buffer is around a megabyte of text, against a few hundred kilobytes for a PNG.
	 */
	@VisibleForTesting
	internal const val KEEP_RECENT = 3

	/**
	 * Writes [snapshot] and returns the file, or `null` if it could not be written.
	 */
	fun write(
		context: Context,
		snapshot: MetricsCsv.Snapshot,
		nowMillis: Long = System.currentTimeMillis(),
		zone: ZoneId = ZoneId.systemDefault(),
	): File? = write(context, snapshot, DIRECTORY, compress = false, nowMillis, zone)

	/**
	 * Writes [snapshot] gzipped, for attaching to a report, or `null` if it could not be written.
	 *
	 * Compressed because it travels: a full buffer is around a megabyte of text and it is highly
	 * compressible -- the timestamps advance by a constant and the magnitudes barely move -- so this
	 * is a large saving on an email attachment for no loss (ADFA-5534, and ADFA-5526 to come).
	 */
	fun writeForReport(
		context: Context,
		snapshot: MetricsCsv.Snapshot,
		nowMillis: Long = System.currentTimeMillis(),
		zone: ZoneId = ZoneId.systemDefault(),
	): File? = write(context, snapshot, REPORT_DIRECTORY, compress = true, nowMillis, zone)

	private fun write(
		context: Context,
		snapshot: MetricsCsv.Snapshot,
		directoryName: String,
		compress: Boolean,
		nowMillis: Long,
		zone: ZoneId,
	): File? {
		val directory = File(context.cacheDir, directoryName)
		return try {
			if (!directory.exists() && !directory.mkdirs()) {
				log.error("Could not create the metrics export directory at {}", directory)
				return null
			}

			val extension = if (compress) COMPRESSED_EXTENSION else "csv"
			val file = File(directory, MetricsFileName.forTime(nowMillis, extension, zone))
			// Streamed, not built into a string: a full buffer is ten thousand rows, and holding the
			// whole file in memory to write it is a megabyte of char array nobody needs. Compressed
			// on the way out for the same reason -- the uncompressed file never has to exist.
			val sink = if (compress) GZIPOutputStream(file.outputStream()) else file.outputStream()
			sink.bufferedWriter().use { writer ->
				MetricsCsv.write(snapshot, zone, writer)
			}
			MetricsSnapshot.pruneTo(directory, KEEP_RECENT, file)
			file
		} catch (io: IOException) {
			log.error("Could not write the metrics export", io)
			null
		}
	}
}
