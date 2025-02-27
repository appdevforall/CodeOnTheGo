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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ide_tooltip_table", primaryKeys = ["tooltipCategory", "tooltipTag"])
data class IDETooltipItem(
    @ColumnInfo(name = "tooltipCategory") val tooltipCategory : String,
    @ColumnInfo(name = "tooltipTag") val tooltipTag : String,
    @ColumnInfo(name = "tooltipSummary") val summary: String,
    @ColumnInfo(name = "tooltipDetail")  val detail: String,
    @ColumnInfo(name = "tooltipButtons") val buttons: ArrayList<Pair<String, String>>
)

