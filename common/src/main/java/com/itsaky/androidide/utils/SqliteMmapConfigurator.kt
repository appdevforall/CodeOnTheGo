package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Enables SQLite's memory-mapped IO (https://sqlite.org/mmap.html) for a database, sized
 * to the whole file so page reads go through the OS's virtual memory instead of repeated
 * read() syscalls. Only applied on 64-bit processes -- a 32-bit process has too little
 * address space to map a documentation-database-sized file. Writes, and reads of any data
 * added past the original file size (e.g. by a plugin), still fall back to the slow path;
 * that's inherent to how SQLite mmap works, not something this call needs to handle.
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
			db.execSQL("PRAGMA mmap_size=$requestedSize")

			val actualSize =
				db.rawQuery("PRAGMA mmap_size", null).use { c ->
					if (c.moveToFirst()) c.getLong(0) else -1L
				}

			Log.i(
				TAG,
				"Enabled mmap for '$dbPath': requested $requestedSize bytes, SQLite granted $actualSize bytes.",
			)
		} catch (e: SQLiteException) {
			Log.w(TAG, "Could not enable mmap for '$dbPath': ${e.message}")
		}
	}
}
