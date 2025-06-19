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
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TooltipDbReader {
    private const val TAG = "TooltipDbReader"
    private const val DATABASE_PATH = "/data/data/com.itsaky.androidide/databases/documentation.db"
    private const val TABLE_NAME = "ide_tooltip_table"

    /**
     * Get a tooltip from the documentation database
     */
    fun getTooltip(context: Context, category: String, tag: String): IDETooltipItem? {
        val dbFile = File(DATABASE_PATH)
        if (!dbFile.exists()) {
            Log.w(TAG, "Database file does not exist: $DATABASE_PATH")
            return null
        }

        val db = try {
            SQLiteDatabase.openDatabase(DATABASE_PATH, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open database: ${e.message}")
            return null
        }

        return try {
            val cursor = db.query(
                TABLE_NAME,
                null,
                "tooltipCategory = ? AND tooltipTag = ?",
                arrayOf(category, tag),
                null,
                null,
                null
            )

            cursor.use {
                if (it.moveToFirst()) {
                    val tooltipCategory = it.getString(it.getColumnIndexOrThrow("tooltipCategory"))
                    val tooltipTag = it.getString(it.getColumnIndexOrThrow("tooltipTag"))
                    val summary = it.getString(it.getColumnIndexOrThrow("tooltipSummary"))
                    val detail = it.getString(it.getColumnIndexOrThrow("tooltipDetail"))
                    val buttonsJson = it.getString(it.getColumnIndexOrThrow("tooltipButtons"))
                    
                    val buttons = parseButtonsJson(buttonsJson)
                    
                    IDETooltipItem(
                        tooltipCategory = tooltipCategory,
                        tooltipTag = tooltipTag,
                        summary = summary,
                        detail = detail,
                        buttons = buttons
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading tooltip from database: ${e.message}")
            null
        } finally {
            db.close()
        }
    }

    /**
     * Get all tooltip items from the database
     */
    fun getAllTooltips(context: Context): List<IDETooltipItem> {
        val dbFile = File(DATABASE_PATH)
        if (!dbFile.exists()) {
            Log.w(TAG, "Database file does not exist: $DATABASE_PATH")
            return emptyList()
        }

        val db = try {
            SQLiteDatabase.openDatabase(DATABASE_PATH, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open database: ${e.message}")
            return emptyList()
        }

        return try {
            val cursor = db.query(
                TABLE_NAME,
                null,
                null,
                null,
                null,
                null,
                "tooltipCategory, tooltipTag ASC"
            )

            val items = mutableListOf<IDETooltipItem>()
            
            cursor.use {
                while (it.moveToNext()) {
                    val tooltipCategory = it.getString(it.getColumnIndexOrThrow("tooltipCategory"))
                    val tooltipTag = it.getString(it.getColumnIndexOrThrow("tooltipTag"))
                    val summary = it.getString(it.getColumnIndexOrThrow("tooltipSummary"))
                    val detail = it.getString(it.getColumnIndexOrThrow("tooltipDetail"))
                    val buttonsJson = it.getString(it.getColumnIndexOrThrow("tooltipButtons"))
                    
                    val buttons = parseButtonsJson(buttonsJson)
                    
                    items.add(IDETooltipItem(
                        tooltipCategory = tooltipCategory,
                        tooltipTag = tooltipTag,
                        summary = summary,
                        detail = detail,
                        buttons = buttons
                    ))
                }
            }
            
            items
        } catch (e: Exception) {
            Log.e(TAG, "Error reading tooltips from database: ${e.message}")
            emptyList()
        } finally {
            db.close()
        }
    }

    /**
     * Get the count of tooltip items in the database
     */
    fun getCount(context: Context): Int {
        val dbFile = File(DATABASE_PATH)
        if (!dbFile.exists()) {
            Log.w(TAG, "Database file does not exist: $DATABASE_PATH")
            return 0
        }

        val db = try {
            SQLiteDatabase.openDatabase(DATABASE_PATH, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open database: ${e.message}")
            return 0
        }

        return try {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null)
            
            cursor.use {
                if (it.moveToFirst()) {
                    it.getInt(0)
                } else {
                    0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting count from database: ${e.message}")
            0
        } finally {
            db.close()
        }
    }

    /**
     * Parse the buttons JSON string into a list of Pairs
     * Expected format: [{"first": "label", "second": "url"}, ...]
     */
    private fun parseButtonsJson(buttonsJson: String): ArrayList<Pair<String, String>> {
        val buttons = ArrayList<Pair<String, String>>()
        
        if (buttonsJson.isBlank()) {
            return buttons
        }
        
        try {
            val jsonArray = JSONArray(buttonsJson)
            for (i in 0 until jsonArray.length()) {
                val buttonObj = jsonArray.getJSONObject(i)
                val label = buttonObj.getString("first")
                val url = buttonObj.getString("second")
                buttons.add(Pair(label, url))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing buttons JSON: $buttonsJson", e)
        }
        
        return buttons
    }
} 