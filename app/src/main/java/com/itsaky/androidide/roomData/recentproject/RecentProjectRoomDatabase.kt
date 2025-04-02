package com.itsaky.androidide.roomData.recentproject

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope

@Database(entities = [RecentProject::class], version = 1, exportSchema = false)
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

        fun getDatabase(context: Context, scope: CoroutineScope): RecentProjectRoomDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecentProjectRoomDatabase::class.java,
                    "RecentProject_database"
                )
                    .addCallback(RecentProjectRoomDatabaseCallback(context, scope))
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
