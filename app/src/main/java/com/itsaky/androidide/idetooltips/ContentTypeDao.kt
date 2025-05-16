package com.itsaky.androidide.idetooltips

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContentTypeDao {
    @Query("SELECT * FROM ContentTypes WHERE id = :id")
    fun getContentTypeById(id: Int): ContentType?

    @Query("SELECT * FROM ContentTypes WHERE `value` = :value")
    fun getContentTypeByValue(value: String): ContentType?
}