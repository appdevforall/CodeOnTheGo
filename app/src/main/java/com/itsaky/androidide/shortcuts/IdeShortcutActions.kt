package com.itsaky.androidide.shortcuts

import android.util.Log
import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.actions.sidebar.PreferencesSidebarAction
import com.itsaky.androidide.actions.sidebar.TerminalSidebarAction
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.activities.PreferencesActivity
import com.itsaky.androidide.activities.TerminalActivity
import com.itsaky.androidide.utils.dismissTopDialog

/**
 * Executes IDE shortcut actions using the actions registry with fallbacks.
 */
class IdeShortcutActions(
	private val actionDataProvider: () -> ActionData?,
) {

	private val actionsRegistry: ActionsRegistry
		get() = ActionsRegistry.getInstance()

	/**
	 * Executes the shortcut action with the given ID, returning whether it ran.
	 */
	fun execute(actionId: String): Boolean {
		val data = actionDataProvider()
		if (data == null) {
			Log.w("IdeShortcutActions", "Missing ActionData for shortcut actionId=$actionId")
			return false
		}

		val context = data.get(Context::class.java)
		if (actionId == TerminalSidebarAction.ID && context is MainActivity) {
			return executeFallback(actionId, data)
		}

		val registry = actionsRegistry as? DefaultActionsRegistry ?: return executeFallback(actionId, data)
		val action = findActionById(actionsRegistry, actionId) ?: return executeFallback(actionId, data)
		action.prepare(data)
		if (!action.enabled) {
			return executeFallback(actionId, data)
		}
		registry.executeAction(action, data)
		return true
	}

	/**
	 * Locates a registered action by ID across all action locations.
	 */
	private fun findActionById(
		actionsRegistry: ActionsRegistry,
		actionId: String,
	): ActionItem? {
		return ActionItem.Location.entries
			.asSequence()
			.mapNotNull { location -> actionsRegistry.findAction(location, actionId) }
			.firstOrNull()
	}

	/**
	 * Executes built-in fallback behaviors when no registered action is found.
	 */
	private fun executeFallback(
		actionId: String,
		data: ActionData,
	): Boolean {
		return when (actionId) {
			ShortcutActionIds.MAIN_CREATE_PROJECT -> {
				(data.get(Context::class.java) as? MainActivity)
					?.showCreateProject()
					?: false
			}

			ShortcutActionIds.MAIN_OPEN_PROJECT -> {
				(data.get(Context::class.java) as? MainActivity)
					?.showOpenProject()
					?: false
			}

			ShortcutActionIds.MAIN_CLONE_REPOSITORY -> {
				(data.get(Context::class.java) as? MainActivity)
					?.showCloneRepository()
					?: false
			}

			ShortcutActionIds.DISMISS_MODAL -> {
				val activity = data.get(Context::class.java) as? FragmentActivity ?: return false
				activity.supportFragmentManager.dismissTopDialog()
			}

			TerminalSidebarAction.ID -> {
				val context = data.get(Context::class.java) ?: return false
				if (context is MainActivity) {
					context.startActivity(Intent(context, TerminalActivity::class.java))
					return true
				}
				TerminalSidebarAction.startTerminalActivity(data, false)
				true
			}

			PreferencesSidebarAction.ID -> {
				val context = data.get(Context::class.java) ?: return false
				context.startActivity(Intent(context, PreferencesActivity::class.java))
				true
			}

			else -> false
		}
	}
}
