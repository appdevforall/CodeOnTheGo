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

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException


// Annotates class to be a Room Database with a table (entity) of the Message class
@Database(entities = [IDETooltipItem::class], version = 2, exportSchema = false)
@TypeConverters(ButtonsConverters::class)
abstract class IDETooltipDatabase : RoomDatabase() {
    abstract fun idetooltipDao(): IDETooltipDao


    companion object {
        @Volatile
        private var instance: IDETooltipDatabase? = null

        fun getDatabase(context: Context): IDETooltipDatabase {
            return instance ?: synchronized(this) {
                val newInstance = Room.databaseBuilder(
                    context.applicationContext,
                    IDETooltipDatabase::class.java,
                    "ide_tooltip_database"
                ).build()
                newInstance.loadData(context)
                instance = newInstance
                newInstance
            }
        }
    }

    fun loadData(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {

            val db = getDatabase(context)
            val dao = db.idetooltipDao()

            val jsonString: String =  loadJsonFromAssets(context, "CoGoTooltips/misc/CoGoTooltips.json")
            val arrayObj: JSONArray = JSONArray(jsonString)
            try {
            for( index in 0 until arrayObj.length()) {
                val jsonObj: JSONObject = arrayObj.get(index) as JSONObject
                val tag = jsonObj.getString("tag")
                val summary = jsonObj.getString("summary")
                val detail = jsonObj.getString("detail")
                val buttonList = jsonObj.get("buttonList") as JSONArray
                val buttonsList = readJsonArrayOfArrays(context, buttonList)
                val item = IDETooltipItem(
                    tooltipTag = tag,
                    summary = summary,
                    detail = detail,
                    buttons = buttonsList
                )

                dao.insert(item)
            }

            val tooltipItemList: List<IDETooltipItem> = dao.getTooltipItems()
            tooltipItemList.forEach { tooltipItem ->
                Log.d(
                    "TooltipRoomDatabase",
                    "after insert database - itemTag = ${tooltipItem.tooltipTag}, " +
                            "summary = ${tooltipItem.summary}, detail=${tooltipItem.detail}"
                )
            }


        } catch(e: Exception) {
                e.printStackTrace()
                Log.e("loadData", "loading tooltip database failed - " + e.localizedMessage)
                }
            }
        }
    }

    fun readJsonArrayOfArrays(context: Context, jsonArray: JSONArray): ArrayList<Pair<String,String>> {
        val resultList = ArrayList<Pair<String,String>>()

        for (i in 0 until jsonArray.length()) {
            val innerArray = jsonArray.getJSONArray(i)
            val pear = Pair(innerArray.getString(0), innerArray.getString(1))
            resultList.add(pear)
        }

        return resultList
    }

    private fun loadJsonFromAssets(context: Context, fileName: String): String {
        val json: String?
        try {
            json = context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (ex: IOException) {
            ex.printStackTrace()
            return ""
        }
        return json
    }


