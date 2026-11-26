package com.itsaky.androidide.roomData.recentproject

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope

@Database(entities = [RecentProject::class, RecentProjectMaintenance::class, DebuggerEndpoint::class], version = 6, exportSchema = false)
abstract class RecentProjectRoomDatabase : RoomDatabase() {
	abstract fun recentProjectDao(): RecentProjectDao

	abstract fun maintenanceDao(): RecentProjectMaintenanceDao

	abstract fun debuggerEndpointDao(): DebuggerEndpointDao

	fun vacuum() {
		val db = openHelper.writableDatabase
		db.execSQL("PRAGMA wal_checkpoint(FULL)")
		db.execSQL("VACUUM")
	}

	private class RecentProjectRoomDatabaseCallback(
		private val context: Context,
		private val scope: CoroutineScope,
	) : Callback()

	companion object {
		@Volatile
		private var instance: RecentProjectRoomDatabase? = null

		private val migration1To2 =
			object : Migration(1, 2) {
				override fun migrate(db: SupportSQLiteDatabase) {
					db.execSQL(
						"ALTER TABLE recent_project_table ADD COLUMN last_modified TEXT NOT NULL DEFAULT '0'",
					)
				}
			}

		private val migration2To3 =
			object : Migration(2, 3) {
				override fun migrate(db: SupportSQLiteDatabase) {
					db.execSQL(
						"ALTER TABLE recent_project_table " +
							"ADD COLUMN template_name TEXT NOT NULL DEFAULT 'unknown'",
					)
					db.execSQL(
						"ALTER TABLE recent_project_table " +
							"ADD COLUMN language TEXT NOT NULL DEFAULT 'unknown'",
					)
				}
			}

		private val migration3To4 =
			object : Migration(3, 4) {
				override fun migrate(db: SupportSQLiteDatabase) {
					// Delete duplicate entries, keeping the one with the highest ID (most recent)
					db.execSQL(
						"DELETE FROM recent_project_table " +
							"WHERE id NOT IN (" +
							"SELECT MAX(id) " +
							"FROM recent_project_table " +
							"GROUP BY location" +
							")",
					)

					// Create the unique index on location
					db.execSQL(
						"CREATE UNIQUE INDEX IF NOT EXISTS `index_recent_project_table_location` " +
							"ON `recent_project_table` (`location`)",
					)
				}
			}

		internal val migration4To5 =
			object : Migration(4, 5) {
				override fun migrate(db: SupportSQLiteDatabase) {
					db.execSQL(
						"CREATE TABLE IF NOT EXISTS `recent_project_maintenance` (`key` TEXT NOT NULL, `completed` INTEGER NOT NULL, PRIMARY KEY(`key`))",
					)
				}
			}

		internal val migration5To6 =
			object : Migration(5, 6) {
				override fun migrate(db: SupportSQLiteDatabase) {
					db.execSQL(
						"CREATE TABLE IF NOT EXISTS `debugger_endpoint_table` (`application_id` TEXT NOT NULL, `project_location` TEXT NOT NULL, `token` TEXT, `port` INTEGER, `updated_at` INTEGER, PRIMARY KEY(`application_id`), FOREIGN KEY(`project_location`) REFERENCES `recent_project_table`(`location`) ON UPDATE CASCADE ON DELETE CASCADE )",
					)
					db.execSQL(
						"CREATE INDEX IF NOT EXISTS `index_debugger_endpoint_table_project_location` ON `debugger_endpoint_table` (`project_location`)",
					)
				}
			}

		internal val migrations: Array<Migration>
			get() = arrayOf(migration1To2, migration2To3, migration3To4, migration4To5, migration5To6)

		fun getDatabase(
			context: Context,
			scope: CoroutineScope,
		): RecentProjectRoomDatabase =
			instance ?: synchronized(this) {
				instance ?: Room
					.databaseBuilder(
						context.applicationContext,
						RecentProjectRoomDatabase::class.java,
						"RecentProject_database",
					).addCallback(RecentProjectRoomDatabaseCallback(context, scope))
					.addMigrations(*migrations)
					.build()
					.also { instance = it }
			}
	}
}
