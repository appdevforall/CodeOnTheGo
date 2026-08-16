package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Enables SQLite's memory-mapped IO (see SQLite's "The Memory-Mapped I/O Extension" doc)
 * for a database, sized to the whole file so page reads go through the OS's virtual
 * memory instead of repeated read() syscalls. Only applied on 64-bit processes -- a
 * 32-bit process has too little address space to map a documentation-database-sized
 * file. Writes, and reads of any data added past the original file size (e.g. by a
 * plugin), still fall back to the slow path; that's inherent to how SQLite mmap works,
 * not something this call needs to handle.
 */
object SqliteMmapConfigurator {
	private val logger = LoggerFactory.getLogger(SqliteMmapConfigurator::class.java)

	/**
	 * Requests memory-mapped IO for [db], sized to its file on disk.
	 *
	 * [db] must already be open, and is left open -- this only issues a PRAGMA on the
	 * caller's connection. The call is synchronous and does both file and SQLite IO, so
	 * keep it off the main thread; in practice callers invoke it right after opening the
	 * database, on whatever thread that open happened.
	 *
	 * Best effort throughout: on a 32-bit process it does nothing, and any failure is
	 * logged and swallowed rather than propagated. SQLite may also grant less than the
	 * requested size, or nothing at all, which is likewise only logged. Nothing here
	 * changes what a subsequent query returns -- only how fast it runs.
	 */
	fun configureMmap(db: SQLiteDatabase) {
		val dbPath = db.path

		if (!Process.is64Bit()) {
			logger.info("Not enabling mmap for '{}': running in a 32-bit process.", dbPath)
			return
		}

		val requestedSize = File(dbPath).length()

		try {
			// PRAGMA mmap_size=N returns the granted size as a result row, and Android's
			// execSQL() rejects any statement that returns data -- rawQuery() is required.
			val actualSize =
				db.rawQuery("PRAGMA mmap_size=$requestedSize", null).use { c ->
					if (c.moveToFirst()) c.getLong(0) else -1L
				}

			if (actualSize > 0) {
				logger.info(
					"Enabled mmap for '{}': requested {} bytes, SQLite granted {} bytes.",
					dbPath,
					requestedSize,
					actualSize,
				)
			} else {
				logger.warn(
					"mmap not enabled for '{}': SQLite granted {} bytes for a request of {}.",
					dbPath,
					actualSize,
					requestedSize,
				)
			}
		} catch (e: Exception) {
			// This is a best-effort read-performance optimization -- no failure here (a
			// missing PRAGMA, a low-memory cursor allocation failure, etc.) should ever
			// break the caller's actual database access.
			logger.warn("Could not enable mmap for '{}'.", dbPath, e)
		}
	}
}
