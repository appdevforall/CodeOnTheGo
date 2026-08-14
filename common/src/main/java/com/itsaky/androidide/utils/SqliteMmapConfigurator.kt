package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.util.Log
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
	private const val TAG = "SqliteMmapConfigurator"

	fun configureMmap(db: SQLiteDatabase) {
		val dbPath = db.path

		if (!Process.is64Bit()) {
			Log.i(TAG, "Not enabling mmap for '$dbPath': running in a 32-bit process.")
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
				Log.i(
					TAG,
					"Enabled mmap for '$dbPath': requested $requestedSize bytes, SQLite granted $actualSize bytes.",
				)
			} else {
				Log.w(
					TAG,
					"mmap not enabled for '$dbPath': SQLite granted $actualSize bytes for a request of $requestedSize.",
				)
			}
		} catch (e: Exception) {
			// This is a best-effort read-performance optimization -- no failure here (a
			// missing PRAGMA, a low-memory cursor allocation failure, etc.) should ever
			// break the caller's actual database access.
			Log.w(TAG, "Could not enable mmap for '$dbPath': ${e.message}")
		}
	}
}
