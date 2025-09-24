
package com.itsaky.androidide.preferences

import android.content.Context
import android.content.Intent
import androidx.preference.Preference
import com.itsaky.androidide.activities.PluginManagerActivity
import com.itsaky.androidide.resources.R.drawable
import com.itsaky.androidide.resources.R.string
import kotlinx.parcelize.Parcelize

@Parcelize
class PluginManagerEntry(
  override val key: String = "idepref_plugin_manager",
  override val title: Int = string.plugin_manager_title,
  override val summary: Int? = string.plugin_manager_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return Preference(context)
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val intent = Intent(context, PluginManagerActivity::class.java)
    // Add flags to prevent multiple instances
    intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    context.startActivity(intent)
    return true
  }
}