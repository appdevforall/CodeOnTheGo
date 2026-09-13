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

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Test

/**
 * The branch logic of [DatabaseVersionResolver], as JVM tests that actually run.
 *
 * The existing coverage lives in `common/src/androidTest`, which no workflow executes -- CI only
 * assembles `:app:assembleV8DebugAndroidTest` and runs two named app classes on Test Lab -- so the
 * `rows > 1` warning and the NULL-major path had no evidence behind them beyond a manual logcat
 * read. These pin the decisions the resolver makes about a malformed table; the SQL ordering itself
 * still belongs in the instrumented file, against a real SQLite.
 */
class DatabaseVersionResolverBranchTest {
	@After
	fun tearDown() {
		unmockkAll()
	}

	private fun database(
		major: Int?,
		rows: Int,
		tableExists: Boolean = true,
	): SQLiteDatabase {
		val existsCursor = mockk<Cursor>(relaxed = true) { every { moveToFirst() } returns tableExists }
		val versionCursor =
			mockk<Cursor>(relaxed = true) {
				every { moveToFirst() } returns true
				every { isNull(0) } returns (major == null)
				every { getInt(0) } returns (major ?: 0)
				every { getInt(1) } returns rows
			}
		return mockk(relaxed = true) {
			every { rawQuery(match { it.contains("sqlite_master") }, any()) } returns existsCursor
			every {
				rawQuery(
					match { it.contains("DocumentationDatabaseVersion") && !it.contains("sqlite_master") },
					any(),
				)
			} returns versionCursor
		}
	}

	@Test
	fun `the newest row wins, and several rows are still answered`() {
		assertThat(DatabaseVersionResolver.resolveMajorVersion(database(major = 2, rows = 3))).isEqualTo(2)
	}

	// A NULL major is indistinguishable to the caller from "no version table": both are null, and
	// WebServer reports "version none" and skips the dictionary either way. For a genuinely old
	// database that is right; for this one it disables dictionary decoding on content that needs it.
	@Test
	fun `a NULL major reads as no declared version`() {
		assertThat(DatabaseVersionResolver.resolveMajorVersion(database(major = null, rows = 1))).isNull()
	}

	@Test
	fun `a NULL major in a multi-row table still reads as no declared version`() {
		assertThat(DatabaseVersionResolver.resolveMajorVersion(database(major = null, rows = 4))).isNull()
	}

	@Test
	fun `an absent table reads as no declared version`() {
		assertThat(
			DatabaseVersionResolver.resolveMajorVersion(database(major = 2, rows = 1, tableExists = false)),
		).isNull()
	}

	// The ordering rule is a cross-repo contract -- docdb-studio reads the same table the same way --
	// so the column it orders by is worth pinning even from this side.
	@Test
	fun `the newest row is chosen by change time, not by rowid alone`() {
		val db = database(major = 2, rows = 2)
		DatabaseVersionResolver.resolveMajorVersion(db)

		io.mockk.verify {
			db.rawQuery(match { it.contains("ORDER BY changeTime DESC") && it.contains("rowid DESC") }, any())
		}
	}
}
