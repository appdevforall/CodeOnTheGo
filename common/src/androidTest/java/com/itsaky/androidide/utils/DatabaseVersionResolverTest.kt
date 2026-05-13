package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
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
				"who TEXT)"
		)
	}

	private fun insertRow(documentationSet: String, changeTime: String, who: String?) {
		db.execSQL(
			"INSERT INTO LastChange (documentationSet, changeTime, who) VALUES (?, ?, ?)",
			arrayOf<Any?>(documentationSet, changeTime, who),
		)
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
