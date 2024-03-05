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

package com.itsaky.androidide.roomData

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {
  @Query("SELECT * FROM message_table ORDER BY message_key ASC")
  fun getAlphabetizedMessages(): List<Message>

  @Query("SELECT message_text FROM message_table WHERE message_key == :key")
  fun getMessage(key : String) : String

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert( message : Message)

  @Query("DELETE FROM message_table")
  suspend fun deleteAll()}