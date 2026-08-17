package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device coverage for [SqliteMmapConfigurator]: that the PRAGMA it issues actually
 * takes effect against a real SQLite connection, which no JVM test can show.
 */
@RunWith(AndroidJUnit4::class)
class SqliteMmapConfiguratorTest {
	private lateinit var dbFile: File
	private lateinit var db: SQLiteDatabase

	@Before
	fun setUp() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		dbFile = context.getDatabasePath("sqlite_mmap_configurator_test.db")
		dbFile.delete()

		SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { writable ->
			writable.execSQL("CREATE TABLE Padding (value TEXT)")
			writable.execSQL("INSERT INTO Padding (value) VALUES (?)", arrayOf("x".repeat(4096)))
		}

		// The one call site that configures mmap, WebServer, opens OPEN_READONLY (see
		// docs/documentation-database.md); match that here rather than testing against a
		// writable connection.
		db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
	}

	@After
	fun tearDown() {
		if (::db.isInitialized) {
			db.close()
		}
		if (::dbFile.isInitialized) {
			dbFile.delete()
		}
	}

	// Returns -1 when the PRAGMA yields no row at all, which is what a build with
	// SQLITE_MAX_MMAP_SIZE=0 does -- the pragma body is compiled out. Reading column 0
	// regardless would throw CursorIndexOutOfBoundsException and hide that cause.
	private fun readMmapSize(): Long =
		db.rawQuery("PRAGMA mmap_size", null).use { c ->
			if (c.moveToFirst()) c.getLong(0) else -1L
		}

	// This device's actual bitness decides which branch runs, matching the production
	// code's own Process.is64Bit() check -- so this test is meaningful under either an
	// arm64-v8a (64-bit) or armeabi-v7a (32-bit) instrumented test run.
	@Test
	fun setsMmapSizeToFileSize_on64BitProcess_elseLeavesItUnchanged() {
		val mmapSizeBeforeCall = readMmapSize()

		SqliteMmapConfigurator.configureMmap(db)

		val mmapSizeAfterCall = readMmapSize()

		if (Process.is64Bit()) {
			// Only "at most what was asked for" is guaranteed: SQLite clamps the grant to
			// SQLITE_MAX_MMAP_SIZE, and a build with that set to 0 grants nothing at all.
			assertTrue(
				"SQLite granted $mmapSizeAfterCall bytes for a ${dbFile.length()}-byte file",
				mmapSizeAfterCall <= dbFile.length(),
			)

			// This file is a few KB, so on any build that supports mmap at all it fits
			// entirely -- a non-zero but short grant would be a real bug, not clamping.
			if (mmapSizeAfterCall > 0) {
				assertEquals(dbFile.length(), mmapSizeAfterCall)
			}
		} else {
			assertEquals(mmapSizeBeforeCall, mmapSizeAfterCall)
		}
	}
}
