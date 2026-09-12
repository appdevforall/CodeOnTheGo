/*
 *  This file is part of Code on the Go.
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

package com.itsaky.androidide.documentation

import android.database.sqlite.SQLiteDatabase
import io.pebbletemplates.pebble.error.LoaderException
import io.pebbletemplates.pebble.loader.Loader
import org.slf4j.LoggerFactory
import java.io.Reader
import java.io.StringReader

/**
 * Resolves Pebble template names against the `Templates` table, so a template can pull in another
 * one with `extends`, `include`, `import` or `embed` (ADFA-5405).
 *
 * This replaces Pebble's `StringLoader`, which treats the name it is handed *as* the template body.
 * That works for one self-contained template and silently breaks every cross-reference:
 * `{% include "nav.peb" %}` asks the loader for "nav.peb", `StringLoader` hands back those eight
 * characters as a template, and the page renders the literal text instead of the partial -- no
 * exception, no log line.
 *
 * Names are `Templates.name` values, matched exactly: the table is a flat namespace with no
 * directories, so there is no prefix, suffix or relative path to apply.
 *
 * @param database Supplies the database to read, or null when none is open. Called on every
 * resolution rather than captured, because the source swaps the handle when a newer database
 * appears; callers resolve under the read lock that a swap excludes, so the handle cannot change
 * mid-render.
 */
internal class DatabaseTemplateLoader(
	private val database: () -> SQLiteDatabase?,
) : Loader<String> {
	override fun getReader(name: String): Reader {
		val database = database() ?: throw LoaderException(null, "No documentation database is open, for template '$name'")

		// Every failure leaves here as a LoaderException, including the ones SQLite raises. Pebble
		// does not wrap what a loader throws -- getTemplate has no catch around its cache's
		// computeIfAbsent -- so a raw SQLiteException would escape the render's PebbleException
		// catch carrying SQL text, and reach a caller that classifies it as "not a template
		// failure" and cannot name the template.
		val body =
			try {
				database.rawQuery(TEMPLATE_QUERY, arrayOf(name)).use { cursor ->
					when {
						// The DDL declares UNIQUE('name'), but the database that is open may be a
						// debug one dropped on the sdcard, which is under no obligation to honour
						// it. Picking row 0 by scan order would render the wrong partial silently,
						// which is the failure ADFA-5405 exists to remove, not to relocate.
						cursor.count > 1 -> {
							throw LoaderException(null, "Template '$name' is shared by more than one database row")
						}

						!cursor.moveToFirst() -> {
							throw LoaderException(null, "Template '$name' not found in the database")
						}

						// getBlob returns a platform type: a NULL content column yields null and
						// the decode below would NPE with no message and no template name.
						else -> {
							cursor.getBlob(0)
								?: throw LoaderException(null, "Template '$name' has no body")
						}
					}
				}
			} catch (e: LoaderException) {
				throw e
			} catch (e: RuntimeException) {
				throw LoaderException(e, "Cannot read template '$name' from the database")
			}

		return StringReader(body.toString(Charsets.UTF_8))
	}

	override fun resourceExists(name: String): Boolean {
		val database = database() ?: return false

		// Not TEMPLATE_QUERY: that copies the whole template blob into a CursorWindow to answer a
		// boolean. Pebble reaches this only through the delegating and servlet loaders, neither of
		// which is wired here, so the cost would be invisible -- which is the reason to get it right.
		//
		// False rather than a throw, on both a database error and a duplicated name. This is
		// Pebble's existence predicate, which a DelegatingLoader uses to decide whether to fall
		// through to the next loader; a throw aborts resolution where a miss would fall back. An
		// earlier version threw here to keep SQL text out of the response, which kept the SQL out
		// but broke the contract to do it -- logging keeps both.
		//
		// A duplicated name answers false because [getReader] refuses to load one: a predicate that
		// said yes to something the reader then rejects is worse than one that says no.
		return try {
			database.rawQuery(COUNT_QUERY, arrayOf(name)).use { cursor ->
				cursor.moveToFirst() && cursor.getInt(0) == 1
			}
		} catch (e: RuntimeException) {
			log.warn("Cannot look up template '{}' in the database", name, e)
			false
		}
	}

	override fun createCacheKey(name: String): String = name

	/** The table is a flat namespace, so a reference resolves to itself -- as with Pebble's own `MemoryLoader`. */
	override fun resolveRelativePath(
		relativePath: String,
		anchorPath: String,
	): String = relativePath

	/** Template bodies are stored as UTF-8 blobs, so the engine's charset setting does not apply. */
	override fun setCharset(charset: String) = Unit

	/** Names are exact `Templates.name` values; decorating them would stop them matching. */
	override fun setPrefix(prefix: String) = Unit

	override fun setSuffix(suffix: String) = Unit

	private companion object {
		private val log = LoggerFactory.getLogger(DatabaseTemplateLoader::class.java)

		private const val TEMPLATE_QUERY = "SELECT content FROM Templates WHERE name = ?"

		/** Counts rather than existence-checks, so a duplicated name can be told from a single one. */
		private const val COUNT_QUERY = "SELECT COUNT(*) FROM Templates WHERE name = ?"
	}
}
