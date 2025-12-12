package com.itsaky.androidide.roomData.recentproject

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope

@Database(entities = [RecentProject::class], version = 3, exportSchema = false)
abstract class RecentProjectRoomDatabase : RoomDatabase() {

    abstract fun recentProjectDao(): RecentProjectDao

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

        fun getDatabase(context: Context, scope: CoroutineScope): RecentProjectRoomDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecentProjectRoomDatabase::class.java,
                    "RecentProject_database"
                )
                    .addCallback(RecentProjectRoomDatabaseCallback(context, scope))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
