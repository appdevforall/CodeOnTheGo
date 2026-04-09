package com.itsaky.androidide.roomData.recentproject

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope

@Database(entities = [RecentProject::class], version = 4, exportSchema = false)
abstract class RecentProjectRoomDatabase : RoomDatabase() {

    abstract fun recentProjectDao(): RecentProjectDao

    fun vacuum() {
        val db = openHelper.writableDatabase
        db.execSQL("PRAGMA wal_checkpoint(FULL)")
        db.execSQL("VACUUM")
    }

    private class RecentProjectRoomDatabaseCallback(
        private val context: Context,
        private val scope: CoroutineScope
    ) : Callback() {

    }

    companion object {
        @Volatile
        private var INSTANCE: RecentProjectRoomDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recent_project_table ADD COLUMN last_modified TEXT NOT NULL DEFAULT '0'"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recent_project_table " +
                    "ADD COLUMN template_name TEXT NOT NULL DEFAULT 'unknown'"
                )
                db.execSQL(
                "ALTER TABLE recent_project_table " +
                    "ADD COLUMN language TEXT NOT NULL DEFAULT 'unknown'"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Delete duplicate entries, keeping the one with the highest ID (most recent)
                db.execSQL(
                    "DELETE FROM recent_project_table " +
                    "WHERE id NOT IN (" +
                    "SELECT MAX(id) " +
                    "FROM recent_project_table " +
                    "GROUP BY location" +
                    ")"
                )
                
                // Create the unique index on location
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_recent_project_table_location` " +
                    "ON `recent_project_table` (`location`)"
                )
            }
        }

        fun getDatabase(context: Context, scope: CoroutineScope): RecentProjectRoomDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecentProjectRoomDatabase::class.java,
                    "RecentProject_database"
                )
                    .addCallback(RecentProjectRoomDatabaseCallback(context, scope))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
