/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.utils

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.FragmentTransaction
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.activities.editor.HelpActivity
import com.itsaky.androidide.fragments.IDETooltipWebviewFragment
import com.itsaky.androidide.fragments.MainFragment

import com.itsaky.androidide.idetooltips.IDETooltipItem

import com.itsaky.androidide.idetooltips.TooltipManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adfa.constants.CONTENT_KEY
import org.adfa.constants.CONTENT_TITLE_KEY

object TooltipUtils {
    private val mainActivity: MainActivity?
        get() = MainActivity.getInstance()

    /**
     * This displays a webpage as the 3rd level tooltip
     *
     * @param   context callers context
     * @param   url    the url as specified in the tooltips database
     * @return  none
     */
    fun showWebPage(context: Context, url: String) {
        val currentActivity = mainActivity ?: return
        val transaction: FragmentTransaction =
            currentActivity.supportFragmentManager.beginTransaction().addToBackStack("WebView")
        val fragment = IDETooltipWebviewFragment()
        val bundle = Bundle()
        bundle.putString(MainFragment.KEY_TOOLTIP_URL, url)
        fragment.arguments = bundle
        transaction.replace(R.id.fragment_containers_parent, fragment)
        transaction.show(fragment)
        transaction.commitAllowingStateLoss()
    }

    /**
     * Shows a tooltip anchored to a generic view.
     */
    fun showIDETooltip(
        context: Context,
        anchorView: View,
        level: Int,
        tooltipItem: IDETooltipItem
    ) {
        TooltipManager.showIDETooltip(context, anchorView, level, tooltipItem) { ctx, url, title ->
            val intent = Intent(ctx, HelpActivity::class.java).apply {
                putExtra(CONTENT_KEY, url)
                putExtra(CONTENT_TITLE_KEY, title)
            }
            ctx.startActivity(intent)
        }
    }


    /**
     * Dumps tooltip database content to Logcat for debugging.
     */
    suspend fun dumpDatabase(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dbPath = context.getDatabasePath("documentation.db").absolutePath
                val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
                
                val query = "SELECT COUNT(*) as count FROM ide_tooltip_table"
                val cursor = db.rawQuery(query, null)
                
                if (cursor.moveToFirst()) {
                    val count = cursor.getInt(cursor.getColumnIndexOrThrow("count"))
                    cursor.close()
                    db.close()
                    
                    withContext(Dispatchers.Main) {
                        if (count == 0) {
                            Log.d("DumpIDEDatabase", "No records found in ide_tooltip_table.")
                        } else {
                            Log.d("DumpIDEDatabase", "Found $count records in ide_tooltip_table.")
                        }
                    }
                } else {
                    cursor.close()
                    db.close()
                    withContext(Dispatchers.Main) {
                        Log.d("DumpIDEDatabase", "No records found in ide_tooltip_table.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("DumpIDEDatabase", "Error accessing tooltip database: ${e.message}")
                }
            }
        }
    }
}