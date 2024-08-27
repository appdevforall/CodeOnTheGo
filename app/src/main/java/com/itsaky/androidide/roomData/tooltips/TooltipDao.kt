/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.roomData.tooltips

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TooltipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tooltip: Tooltip)

    @Query("SELECT * FROM tooltip_table WHERE word = :word")
    suspend fun getTooltipWord(word: String): Tooltip?

    @Query("DELETE FROM tooltip_table WHERE word = :word")
    suspend fun deleteByWord(word: String)

    @Query("DELETE FROM tooltip_table")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM tooltip_table")
    suspend fun getCount(): Int
}