package com.itsaky.androidide.shortcuts

import android.util.Log
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry

/**
 * Executes IDE shortcut actions using the actions registry.
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

		val registry = actionsRegistry as? DefaultActionsRegistry ?: return false
		val action = findActionById(actionsRegistry, actionId) ?: return false
		action.prepare(data)
		if (!action.enabled) return false
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
}
