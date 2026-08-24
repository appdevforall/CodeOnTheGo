package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseVersionResolverTest {
	private lateinit var db: SQLiteDatabase

	@Before
	fun setUp() {
		db = SQLiteDatabase.openOrCreateDatabase(":memory:", null)
	}

	@After
	fun tearDown() {
		db.close()
	}

	private fun createTable() {
		db.execSQL(
			"CREATE TABLE LastChange (" +
				"documentationSet TEXT, " +
				"changeTime TEXT, " +
				"who TEXT)",
		)
	}

	private fun insertRow(
		documentationSet: String,
		changeTime: String,
		who: String?,
	) {
		db.execSQL(
			"INSERT INTO LastChange (documentationSet, changeTime, who) VALUES (?, ?, ?)",
			arrayOf<Any?>(documentationSet, changeTime, who),
		)
	}

	private fun createVersionTable() {
		db.execSQL(
			"CREATE TABLE DocumentationDatabaseVersion (" +
				"major INT NOT NULL, " +
				"minor INT NOT NULL, " +
				"patch INT NOT NULL, " +
				"who TEXT NOT NULL, " +
				"comment TEXT NOT NULL, " +
				"changeTime TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
		)
	}

	private fun insertVersion(
		major: Int,
		minor: Int,
		patch: Int,
	) {
		db.execSQL(
			"INSERT INTO DocumentationDatabaseVersion (major, minor, patch, who, comment) VALUES (?, ?, ?, 'test', 'test')",
			arrayOf<Any?>(major, minor, patch),
		)
	}

	// ADFA-5220: a database built before the version table existed has to read as unversioned, not
	// as an error -- that is how WebServer decides not to look for a compression dictionary.
	@Test
	fun majorVersionIsNull_whenVersionTableMissing() {
		assertNull(DatabaseVersionResolver.resolveMajorVersion(db))
	}

	@Test
	fun majorVersionIsNull_whenVersionTableEmpty() {
		createVersionTable()
		assertNull(DatabaseVersionResolver.resolveMajorVersion(db))
	}

	@Test
	fun majorVersionIsRead_whenDeclared() {
		createVersionTable()
		insertVersion(2, 0, 0)
		assertEquals(2, DatabaseVersionResolver.resolveMajorVersion(db))
	}

	// Both directions, deliberately. Merging these into the downgrade case alone left a suite that
	// MIN(major) would also have passed -- every expectation happened to be the lowest major present
	// -- so nothing pinned the ordering the whole design rests on.
	@Test
	fun majorVersionIsTheRowWrittenLast_whenTheLastRowIsHigher() {
		createVersionTable()
		insertVersion(2, 0, 0)
		insertVersion(3, 1, 4)
		assertEquals(3, DatabaseVersionResolver.resolveMajorVersion(db))
	}

	// ...and when it is lower, which MAX(major) would get wrong: a rebuild from an older content set
	// is a downgrade and has to read as one.
	@Test
	fun majorVersionIsTheRowWrittenLast_whenTheLastRowIsADowngrade() {
		createVersionTable()
		insertVersion(3, 0, 0)
		insertVersion(2, 0, 0)
		assertEquals(2, DatabaseVersionResolver.resolveMajorVersion(db))
	}

	// Malformed twice over: several rows, and the last one has no major. The shipped DDL forbids that
	// -- which is the point, since this reader defends against files another producer wrote -- so the
	// table is created here without the NOT NULL. The count is read before the NULL check, so a file
	// like this still warns instead of being reported as having no version table at all.
	@Test
	fun majorVersionIsNull_whenTheLastRowHasNoMajor() {
		db.execSQL(
			"CREATE TABLE DocumentationDatabaseVersion (" +
				"major INT, minor INT, patch INT, who TEXT, comment TEXT, changeTime TIMESTAMP)",
		)
		insertVersion(2, 0, 0)
		db.execSQL(
			"INSERT INTO DocumentationDatabaseVersion (major, minor, patch, who, comment) " +
				"VALUES (NULL, 0, 0, 'test', 'test')",
		)

		assertNull(DatabaseVersionResolver.resolveMajorVersion(db))
	}

	@Test
	fun returnsWholedbRow_whenPresent() {
		createTable()
		insertRow("wholedb", "2026-05-09 02:00:20", "hal")
		insertRow("tooltips-ide", "2026-05-09 02:00:20", "hal")

		assertEquals(
			"2026-05-09 02:00:20 hal",
			DatabaseVersionResolver.resolveDatabaseVersion(db),
		)
	}

	@Test
	fun fallsBackToLatestRow_whenWholedbMissing() {
		createTable()
		insertRow("tooltips-ide", "2026-05-09 02:00:20", "hal")
		insertRow("content-y", "2026-05-01 17:42:29", "hal")
		insertRow("tooltips-java", "2026-05-09 01:58:37", "hal")

		assertEquals(
			"2026-05-09 02:00:20 (tooltips-ide) hal",
			DatabaseVersionResolver.resolveDatabaseVersion(db),
		)
	}

	@Test
	fun returnsVersionUnknown_whenTableEmpty() {
		createTable()

		assertEquals(
			DatabaseVersionResolver.VERSION_UNKNOWN,
			DatabaseVersionResolver.resolveDatabaseVersion(db),
		)
	}

	@Test
	fun returnsVersionUnknown_whenTableMissing() {
		// Intentionally do not create the LastChange table.
		assertEquals(
			DatabaseVersionResolver.VERSION_UNKNOWN,
			DatabaseVersionResolver.resolveDatabaseVersion(db),
		)
	}

	@Test
	fun handlesNullWho_onWholedbRow() {
		createTable()
		insertRow("wholedb", "2026-05-09 02:00:20", null)

		assertEquals(
			"2026-05-09 02:00:20",
			DatabaseVersionResolver.resolveDatabaseVersion(db),
		)
	}

	@Test
	fun handlesNullWho_onFallbackRow() {
		createTable()
		insertRow("tooltips-ide", "2026-05-09 02:00:20", null)

		assertEquals(
			"2026-05-09 02:00:20 (tooltips-ide)",
			DatabaseVersionResolver.resolveDatabaseVersion(db),
		)
	}
}
