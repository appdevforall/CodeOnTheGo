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

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Annotates class to be a Room Database with a table (entity) of the Message class
@Database(entities = [IDETooltipItem::class], version = 1, exportSchema = false)
abstract class IDETooltipRoomDatabase : RoomDatabase() {

    abstract fun idetooltipDao(): IDETooltipDao


    companion object {
        @Volatile private var instance: IDETooltipRoomDatabase? = null

        fun getDatabase(context: Context): IDETooltipRoomDatabase {
            return instance ?: synchronized(this) {
                val newInstance = Room.databaseBuilder(
                    context.applicationContext,
                    IDETooltipRoomDatabase::class.java,
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
            populateDatabase(context)
        }
    }

        private suspend fun populateDatabase(context: Context) {
            val db = getDatabase(context)
            val dao = db.idetooltipDao()

            //don't do anything if there is already records in the database
            if(dao.getCount() > 0) {
                return
            }
        // Delete all content here.
            dao.deleteAll()
            try {
            //// JMT for demo only
                val item1 = IDETooltipItem(
                    tag = 100,
                    summary = "Create a new empty project or use a template.",
                    detail = "When you start a new project, Code on the Go creates the right structure for your files. To get started quickly, you can begin a new project using a template that contains starter code and features for a specific kind of app. ",
                    uri = "file://100.html")
                    //'<a href=\"file://projects_top.html\">Learn more about projects</a><br><a href=\"file://templates_top.html\">Learn more about projects and templates</a><br><a href=\"file://IntroToActivities.html\">Learn about activities</a>'))

                dao.insert(item1)


//                val userList: JSONArray =
//                    context.resources.openRawResource(R.raw.tooltips.csv).bufferedReader().use {
//                        JSONArray(it.readText())
//                    }
//
//                userList.takeIf { it.length() > 0 }?.let { list ->
//                    for (index in 0 until list.length()) {
//                        val userObj = list.getJSONObject(index)
//                        tooltipDao.insert(
//                            TooltipItem(
//                                userObj.getInt("key"),
//                                userObj.getString("summary"),
//                                userObj.getString("detail"),
//                                userObj.getString("uri")
//                            )
//                        )
//
//                    }
                    Log.e("User App", "successfully pre-populated users into database")

            } catch (exception: Exception) {
                Log.e(
                    "User App",
                    exception.localizedMessage ?: "failed to pre-populate users into database"
                )
            }
            val IDETooltipItemList: List<IDETooltipItem> = dao.getTooltipItems()
            IDETooltipItemList.forEach { tooltipItem ->
                Log.d(
                    "TooltipRoomDatabase",
                    "after insert database - itemTag = ${tooltipItem.tag}, " +
                            "summary = ${tooltipItem.summary}, detail=${tooltipItem.detail}, uri=${tooltipItem.uri}"
                )
            }
        }

//        private fun populateDatabaseFromCSV(db: TooltipRoomDatabase) {
//            // Reading the CSV file from assets
//            val inputStream = context.assets.open("tooltips.csv")
//            val reader = BufferedReader(InputStreamReader(inputStream))
//            var line: String?
//            val TABLE_NAME = "tooltips.db"
//
//            try {
//                // Loop through the lines of the CSV
//                while (reader.readLine().also { line = it } != null) {
//                    // Split the line by comma
//                    val parts = line!!.split(",")
//                    if (parts.size == 2) {
//                        val id = parts[0].toInt()
//                        val value = parts[1]
//
//                        // Insert into the database
//                        val insertQuery = "INSERT INTO $TABLE_NAME ($COLUMN_ID, $COLUMN_STRING) VALUES (?, ?)"
//                        db.execSQL(insertQuery, arrayOf(id, value))
//                    }
//                }
//            } finally {
//                reader.close() // Ensure the reader is closed after use
//            }
//        }
//    }
//        companion object {
//            // Singleton prevents multiple instances of database opening at the
//            // same time.
//            @Volatile
//            private var INSTANCE: TooltipRoomDatabase? = null
//
//            fun getDatabase(context: Context, scope: CoroutineScope): TooltipRoomDatabase {
//                // if the INSTANCE is not null, then return it,
//                // if it is, then create the database
//                return INSTANCE ?: synchronized(this) {
//                    val instance = Room.databaseBuilder(
//                        context.applicationContext,
//                        TooltipRoomDatabase::class.java,
//                        "Message_database"
//                    ).addCallback(TooltipDatabaseCallback(context, scope))
//                        .build()
//                    INSTANCE = instance
//                    // return instance
//                    instance
//                }
//            }
//        }
//    }
}