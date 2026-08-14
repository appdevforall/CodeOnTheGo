package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Process
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

// Regression coverage for the real on-device failure (ADFA-4979): Android's
// SQLiteDatabase.execSQL() rejects any statement that returns a result row, and
// `PRAGMA mmap_size=N` does exactly that -- it threw SQLiteException on every call
// until configureMmap() switched to rawQuery(). This locks in that the function
// never lets such a failure escape to the caller.
class SqliteMmapConfiguratorTest {
	@Before
	fun setUp() {
		mockkStatic(Process::class)
		every { Process.is64Bit() } returns true

		mockkStatic(Log::class)
		every { Log.i(any(), any()) } returns 0
		every { Log.w(any(), any<String>()) } returns 0
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `configureMmap swallows a SQLiteException instead of propagating`() {
		val db = mockk<SQLiteDatabase>()
		every { db.path } returns "/nonexistent/documentation.db"
		every { db.rawQuery(any(), any()) } throws SQLiteException("simulated failure")

		SqliteMmapConfigurator.configureMmap(db) // must not throw

		verify { Log.w(any(), any<String>()) }
	}

	@Test
	fun `configureMmap swallows a non-SQLite RuntimeException too`() {
		val db = mockk<SQLiteDatabase>()
		every { db.path } returns "/nonexistent/documentation.db"
		every { db.rawQuery(any(), any()) } throws IllegalStateException("simulated cursor failure")

		SqliteMmapConfigurator.configureMmap(db) // must not throw

		verify { Log.w(any(), any<String>()) }
	}
}
