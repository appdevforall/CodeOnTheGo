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

package com.itsaky.androidide.memprof

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Starts the profileable variant's measurement as the process comes up.
 *
 * A content provider is created before `Application.onCreate`, the earliest hook available without
 * editing shared sources, and cold-start allocation is part of what the measurement is for.
 */
class MemprofInitProvider : ContentProvider() {
	override fun onCreate(): Boolean {
		context?.applicationContext?.let(MemprofSession::install)
		return true
	}

	override fun query(
		uri: Uri,
		projection: Array<out String>?,
		selection: String?,
		selectionArgs: Array<out String>?,
		sortOrder: String?,
	): Cursor? = null

	override fun getType(uri: Uri): String? = null

	override fun insert(
		uri: Uri,
		values: ContentValues?,
	): Uri? = null

	override fun delete(
		uri: Uri,
		selection: String?,
		selectionArgs: Array<out String>?,
	): Int = 0

	override fun update(
		uri: Uri,
		values: ContentValues?,
		selection: String?,
		selectionArgs: Array<out String>?,
	): Int = 0
}
