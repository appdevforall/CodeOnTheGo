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

		return database.rawQuery(TEMPLATE_QUERY, arrayOf(name)).use { cursor ->
			if (!cursor.moveToFirst()) {
				throw LoaderException(null, "Template '$name' not found in the database")
			}
			StringReader(cursor.getBlob(0).toString(Charsets.UTF_8))
		}
	}

	override fun resourceExists(name: String): Boolean {
		val database = database() ?: return false

		// Not TEMPLATE_QUERY: that copies the whole template blob into a CursorWindow to answer a
		// boolean. Pebble reaches this only through the delegating and servlet loaders, neither of
		// which is wired here, so the cost would be invisible -- which is the reason to get it right.
		return database.rawQuery(EXISTS_QUERY, arrayOf(name)).use { it.moveToFirst() }
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
		private const val TEMPLATE_QUERY = "SELECT content FROM Templates WHERE name = ?"
		private const val EXISTS_QUERY = "SELECT 1 FROM Templates WHERE name = ? LIMIT 1"
	}
}
