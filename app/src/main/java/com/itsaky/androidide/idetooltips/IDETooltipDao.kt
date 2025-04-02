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

package com.itsaky.androidide.idetooltips

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IDETooltipDao {
  @Query("SELECT * FROM ide_tooltip_table ORDER BY tooltipTag ASC")
  fun getTooltipItems(): List<IDETooltipItem>

  @Query("SELECT tooltipSummary FROM ide_tooltip_table WHERE tooltipTag == :tooltipTag")
  fun getSummary(tooltipTag : String) : String

  @Query("SELECT tooltipDetail FROM ide_tooltip_table WHERE tooltipTag == :tooltipTag")
  fun getDetail(tooltipTag : String) : String

  //@Query("SELECT tooltipButtons FROM ide_tooltip_table WHERE tooltipTag == :tooltipTag")
  //fun getButtons(tooltipTag: String) : ArrayList<Pair<String, String>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(IDETooltipItem: IDETooltipItem)

  @Query("SELECT * FROM ide_tooltip_table WHERE tooltipTag == :tooltipTag")
  suspend fun getTooltip(tooltipTag: String) : IDETooltipItem?

  @Query("DELETE FROM ide_tooltip_table")
  suspend fun deleteAll()

  @Query("SELECT COUNT(*) FROM ide_tooltip_table")
  suspend fun getCount(): Int
}