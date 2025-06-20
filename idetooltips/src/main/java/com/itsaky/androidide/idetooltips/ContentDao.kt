package com.itsaky.androidide.idetooltips

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Define the Data Access Objects (DAOs) (same as before)
@Dao
interface ContentDao {
    @Query("SELECT * FROM Content WHERE path = :path")
    fun getContent(path: String): Content? // Use Flow for reactive updates

    @Query("SELECT * FROM Content WHERE path = :path AND languageID = :languageId")
    suspend fun getContentByPathAndLanguage(path: String, languageId: Int): Content?
}
