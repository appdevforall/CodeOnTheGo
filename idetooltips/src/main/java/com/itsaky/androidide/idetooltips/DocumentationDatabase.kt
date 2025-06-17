package com.itsaky.androidide.idetooltips

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Define the Room Database (using KSP for Room)
@Database(entities = [Content::class, Language::class, ContentType::class], version = 1, exportSchema = false)
abstract class DocumentationDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentDao
    abstract fun languageDao():LanguageDao
    abstract fun contentTypeDao(): ContentTypeDao

    companion object {
        @Volatile
        private var INSTANCE: DocumentationDatabase? = null

        fun getDatabase(context: Context): DocumentationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DocumentationDatabase::class.java,
                    "documentation.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}