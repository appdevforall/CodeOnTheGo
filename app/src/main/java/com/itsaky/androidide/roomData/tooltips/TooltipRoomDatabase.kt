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

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.itsaky.androidide.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import org.json.JSONException

@Database(entities = [Tooltip::class], version = 1, exportSchema = false)
abstract class TooltipRoomDatabase : RoomDatabase() {

    abstract fun tooltipDao(): TooltipDao

    private class TooltipRoomDatabaseCallback(
        private val context: Context,
        private val scope: CoroutineScope
    ) : Callback() {

        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)

            INSTANCE?.let { database ->
                scope.launch(Dispatchers.IO) {
                    populateDatabase(context, database.tooltipDao())
                }
            }
        }

        suspend fun populateDatabase(context: Context, tooltipDao: TooltipDao) {
            tooltipDao.deleteAll()

            val tooltips = loadTooltipsFromRaw(context)

            tooltips?.let {
                insertTooltipsIntoDatabase(tooltipDao, it)
            } ?: Log.e("User App", "No tooltips found to pre-populate the database")
        }

        private fun loadTooltipsFromRaw(context: Context): JSONArray? {
            return try {
                context.resources.openRawResource(R.raw.tooltips).bufferedReader().use {
                    val jsonString = it.readText()
                    JSONArray(jsonString)
                }
            } catch (e: JSONException) {
                Log.e("User App", "Error parsing tooltips JSON: ${e.localizedMessage}")
                null
            } catch (e: IOException) {
                Log.e("User App", "Error reading tooltips file: ${e.localizedMessage}")
                null
            }
        }

        private suspend fun insertTooltipsIntoDatabase(
            tooltipDao: TooltipDao,
            tooltips: JSONArray
        ) {
            try {
                for (i in 0 until tooltips.length()) {
                    val tooltipObj = tooltips.getJSONObject(i)
                    val tooltip = Tooltip(
                        id = 0,
                        word = tooltipObj.getString("word"),
                        descriptionShort = tooltipObj.getString("description_short"),
                        descriptionLong = tooltipObj.getString("description_long"),
                        descriptionFull = tooltipObj.getString("description_full")
                    )
                    tooltipDao.insert(tooltip)
                }
                Log.i("User App", "Successfully pre-populated tooltips into the database")
            } catch (e: JSONException) {
                Log.e(
                    "User App",
                    "Error inserting tooltips into the database: ${e.localizedMessage}"
                )
            } catch (e: Exception) {
                Log.e(
                    "User App",
                    "Unexpected error during database insertion: ${e.localizedMessage}"
                )
            }
        }
    }

    companion object {

        @Volatile
        private var INSTANCE: TooltipRoomDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): TooltipRoomDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TooltipRoomDatabase::class.java,
                    "Tooltip_database"
                ).addCallback(TooltipRoomDatabaseCallback(context, scope)).build()
                    .also { INSTANCE = it }
            }
        }
    }
}