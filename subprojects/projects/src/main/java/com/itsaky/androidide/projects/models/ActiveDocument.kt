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

package com.itsaky.androidide.projects.models

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.nio.file.Path
import java.time.Instant

/**
 * A document that is opened in the editor.
 *
 * @author Akash Yadav
 */
open class ActiveDocument(
	val file: Path,
	version: Int,
	modified: Instant,
	content: String = "",
) {
	private data class Snapshot(
		val version: Int,
		val modified: Instant,
		val content: String,
	)

	/*
	 * One volatile reference, so a reader can never pair a new version with the old content. The editor
	 * dispatches change events from a background coroutine per edit, so two edits in one frame do reach
	 * this concurrently.
	 */
	@Volatile
	private var snapshot = Snapshot(version, modified, content)

	val version: Int
		get() = snapshot.version

	val modified: Instant
		get() = snapshot.modified

	val content: String
		get() = snapshot.content

	/**
	 * Publishes [content] at [version], or returns false if [version] is older than what is already
	 * published.
	 *
	 * A version that moves backwards makes the Kotlin index mint a second `KtFile` for text that never
	 * changed, which is what surfaced as redeclaration errors across a whole file (ADFA-5231).
	 */
	internal fun update(
		version: Int,
		content: String,
	): Boolean {
		synchronized(this) {
			if (version < snapshot.version) return false
			snapshot = Snapshot(version, Instant.now(), content)
			return true
		}
	}

	fun inputStream(): BufferedInputStream = content.byteInputStream().buffered()

	fun reader(): BufferedReader = content.reader().buffered()
}
