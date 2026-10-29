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

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What every exported metrics file is called (ADFA-5531).
 *
 * One rule for the CSV and the chart image alike, differing only in extension, so a pair exported
 * together sorts together and a consumer can tell when a file was written without opening it.
 *
 * Underscores and no offset, which is what makes it a filename rather than a timestamp: it has to
 * survive every filesystem the IDE can write to and sort lexicographically in a directory listing.
 * The times *inside* the file are ISO 8601 -- see [MetricsCsv].
 */
object MetricsFileName {
	/** `YYYY_MM_DD_HH_MM_SS_SSS`, as the format section of ADFA-5531 specifies it. */
	private val PATTERN: DateTimeFormatter =
		DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss_SSS", Locale.ROOT)

	/**
	 * The name for a file written at [atMillis], with [extension] and no leading dot.
	 *
	 * Local time, because this is the name a person reads in a share sheet or a file manager.
	 */
	fun forTime(
		atMillis: Long,
		extension: String,
		zone: ZoneId = ZoneId.systemDefault(),
	): String = "${PATTERN.format(Instant.ofEpochMilli(atMillis).atZone(zone))}.$extension"
}
