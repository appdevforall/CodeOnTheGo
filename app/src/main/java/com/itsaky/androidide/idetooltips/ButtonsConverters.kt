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

import androidx.room.TypeConverter
import com.google.common.reflect.TypeToken
import com.google.gson.Gson

class ButtonsConverters {
    private val gson = Gson()

    @TypeConverter
    fun fromButtons(pairList: ArrayList<Pair<String, String>>?): String? {
        return gson.toJson(pairList)
    }

    @TypeConverter
    fun toButtons(pairListString: String?): ArrayList<Pair<String, String>>? {
        if (pairListString == null) {
            return java.util.ArrayList()
        }

        val type = object : TypeToken<ArrayList<Pair<String, String>>>() {}.type
        return gson.fromJson(pairListString, type)
    }
}