package com.itsaky.androidide.idetooltips

import androidx.room.Dao
import androidx.room.Query

@Dao
interface LanguageDao {
    @Query("SELECT * FROM Languages WHERE `value` = :value")
    suspend fun getLanguageByValue(value: String): Language?
}
