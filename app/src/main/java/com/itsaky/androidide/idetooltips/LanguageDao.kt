package com.itsaky.androidide.idetooltips

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LanguageDao {
    @Query("SELECT * FROM Languages")
    fun getAll(): Flow<List<Language>>

    @Query("SELECT * FROM Languages WHERE id = :id")
    suspend fun getLanguageById(id: Int): Language?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(language: Language): Long

    @Query("SELECT * FROM Languages WHERE `value` = :value")
    suspend fun getLanguageByValue(value: String): Language?
}
