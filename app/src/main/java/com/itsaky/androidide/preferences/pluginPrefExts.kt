
package com.itsaky.androidide.preferences

import android.content.Context
import android.content.Intent
import androidx.preference.Preference
import com.itsaky.androidide.activities.PluginManagerActivity
import com.itsaky.androidide.activities.PluginScreenNavigator
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_PLUGIN_MANAGER
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.resources.R.drawable
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.utils.ILogger
import com.itsaky.androidide.utils.flashError
import kotlinx.parcelize.Parcelize

@Parcelize
class PluginManagerEntry(
	override val key: String = "idepref_plugin_manager",
	override val title: Int = string.plugin_manager_title,
	override val summary: Int? = string.plugin_manager_summary,
	override val tooltipTag: String = PREFS_PLUGIN_MANAGER,
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

/**
 * A Configuration row contributed by a plugin through
 * [com.itsaky.androidide.plugins.extensions.SettingsExtension]. Tapping it asks
 * [PluginScreenNavigator] to mount the plugin's own fragment full-screen; the row itself only holds
 * the data, builds the widget, and reports a failed launch.
 *
 * Deliberately not a [BasePreference]: [IPreference.title] is an `@StringRes` resolved against the
 * IDE's resources, while a plugin's title is a runtime string, so this row builds its own
 * [Preference].
 *
 * Carries only strings - no click lambda - so it can rebuild the launch after process death,
 * unlike [SimpleClickablePreference], whose callback is dropped from the parcel.
 *
 * A data class on purpose: `PreferencesActivity` compares the contributed rows across resumes to
 * decide whether the preference tree needs rebuilding.
 */
@Parcelize
data class PluginSettingsEntryPreference(
	val pluginId: String,
	val entryId: String,
	val titleText: String,
	val summaryText: String?,
	val fragmentClassName: String,
) : IPreference() {
	// Length-prefixing pluginId makes the split point unambiguous regardless of what
	// characters either id contains - a plain "$pluginId.$entryId" join can collide, e.g.
	// ("com.example", "ai.agent") and ("com.example.ai", "agent") would otherwise both
	// produce "com.example.ai.agent".
	override val key: String
		get() = "idepref_plugin_settings_${pluginId.length}:$pluginId:$entryId"

	// Unused: the row sets a literal title in onCreateView rather than resolving an IDE resource.
	override val title: Int
		get() = 0

	override fun onCreateView(context: Context): Preference {
		val preference = Preference(context)
		preference.key = key
		preference.title = titleText
		preference.summary = summaryText
		// No icon in v1: leave the slot unreserved so the row aligns with PluginManagerEntry.
		preference.isIconSpaceReserved = false
		preference.setOnPreferenceClickListener { onClick(it.context) }
		return preference
	}

	private fun onClick(context: Context): Boolean {
		val opened =
			PluginScreenNavigator.openPluginScreen(
				context = context,
				pluginId = pluginId,
				fragmentClassName = fragmentClassName,
				title = titleText,
			)

		// Without this the row looks dead: the tap would do nothing the user can see.
		if (!opened) {
			flashError(string.msg_open_plugin_settings_failed)
		}

		// The click was ours either way, so don't let androidx-preference fall through to its own
		// fragment/intent handling.
		return true
	}
}

/**
 * The preference rows contributed by enabled plugins. Empty when no plugin contributes any, and
 * also while the asynchronous plugin load is still in flight - `PreferencesActivity` re-checks on
 * resume and rebuilds the tree if the set changed.
 *
 * A duplicate [PluginSettingsEntryPreference.key] - two plugins colliding, or one plugin returning
 * two entries with the same id - is dropped (keeping the first) rather than reaching the
 * Preferences screen's tree: that tree asserts every key is unique and crashes if it isn't, which
 * is the right behaviour for a first-party bug but not for third-party plugin data.
 *
 * [pluginManager] defaults to the running IDE's instance; tests pass their own.
 */
internal fun pluginSettingsPreferences(
	pluginManager: PluginManager? = IDEApplication.getPluginManager(),
): List<PluginSettingsEntryPreference> {
	val seenKeys = mutableSetOf<String>()
	return pluginManager
		?.getPluginSettingsEntries()
		?.map { (pluginId, entry) ->
			PluginSettingsEntryPreference(
				pluginId = pluginId,
				entryId = entry.id,
				titleText = entry.title,
				summaryText = entry.summary,
				fragmentClassName = entry.fragmentClassName,
			)
		}
		?.filter { entry ->
			seenKeys.add(entry.key).also { isNew ->
				if (!isNew) {
					ILogger.ROOT.warn("Dropping plugin settings entry with a duplicate key: {}", entry.key)
				}
			}
		}
		.orEmpty()
}
