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

package com.itsaky.androidide.IDETooltips

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IDETooltipDao {
  @Query("SELECT * FROM ide_tooltip_table ORDER BY itemTag ASC")
  fun getTooltipItems(): List<IDETooltipItem>

  @Query("SELECT tooltipSummary FROM ide_tooltip_table WHERE itemTag == :tag")
  fun getSummary(tag : String) : String

  @Query("SELECT tooltipDetail FROM ide_tooltip_table WHERE itemTag == :tag")
  fun getDetail(tag : String) : String

  @Query("SELECT tooltipURI FROM ide_tooltip_table WHERE itemTag == :tag")
  fun getURI(tag : String) : String

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(IDETooltipItem: IDETooltipItem)

  @Query("DELETE FROM ide_tooltip_table")
  suspend fun deleteAll()

  @Query("SELECT COUNT(*) FROM ide_tooltip_table")
  suspend fun getCount(): Int
}