package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Process
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Regression coverage for the real on-device failure (ADFA-4979): Android's
 * `SQLiteDatabase.execSQL()` rejects any statement that returns a result row, and
 * `PRAGMA mmap_size=N` does exactly that -- it threw `SQLiteException` on every call
 * until [SqliteMmapConfigurator.configureMmap] switched to `rawQuery()`. These tests
 * lock in that such a failure never escapes to the caller, whatever its type.
 */
class SqliteMmapConfiguratorTest {
	@Before
	fun setUp() {
		// Force the 64-bit branch so the PRAGMA is actually attempted; the JVM test
		// runner's bitness would otherwise decide which path runs.
		mockkStatic(Process::class)
		every { Process.is64Bit() } returns true
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `configureMmap swallows a SQLiteException instead of propagating`() {
		assertThat(configureMmapFailingWith(SQLiteException("simulated failure"))).isNull()
	}

	@Test
	fun `configureMmap swallows a non-SQLite RuntimeException too`() {
		assertThat(configureMmapFailingWith(IllegalStateException("simulated cursor failure"))).isNull()
	}

	/**
	 * Runs [SqliteMmapConfigurator.configureMmap] against a database whose `rawQuery()` throws
	 * [failure], returning whatever escaped -- `null` when nothing did.
	 *
	 * The path must name a real, non-empty file: `configureMmap` returns early when the file
	 * reports a length of 0, so pointing this at a nonexistent path would skip the PRAGMA
	 * entirely and the tests would pass without ever reaching the code they cover. The
	 * `verify` below is what keeps that from silently regressing again.
	 */
	private fun configureMmapFailingWith(failure: Throwable): Throwable? {
		val dbFile = File.createTempFile("sqlite_mmap_configurator_test", ".db")
		return try {
			dbFile.writeBytes(ByteArray(4096))

			val db = mockk<SQLiteDatabase>()
			every { db.path } returns dbFile.absolutePath
			every { db.rawQuery(any(), any()) } throws failure

			runCatching { SqliteMmapConfigurator.configureMmap(db) }.exceptionOrNull().also {
				verify { db.rawQuery(any(), any()) }
			}
		} finally {
			dbFile.delete()
		}
	}
}
