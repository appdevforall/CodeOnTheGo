package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SqliteMmapConfiguratorTest {
	private lateinit var dbFile: File
	private lateinit var db: SQLiteDatabase

	@Before
	fun setUp() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		dbFile = context.getDatabasePath("sqlite_mmap_configurator_test.db")
		dbFile.delete()
		db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
		db.execSQL("CREATE TABLE Padding (value TEXT)")
		db.execSQL("INSERT INTO Padding (value) VALUES (?)", arrayOf("x".repeat(4096)))
	}

	@After
	fun tearDown() {
		db.close()
		dbFile.delete()
	}

	private fun readMmapSize(): Long =
		db.rawQuery("PRAGMA mmap_size", null).use { c ->
			c.moveToFirst()
			c.getLong(0)
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
			assertEquals(dbFile.length(), mmapSizeAfterCall)
		} else {
			assertEquals(mmapSizeBeforeCall, mmapSizeAfterCall)
		}
	}
}
