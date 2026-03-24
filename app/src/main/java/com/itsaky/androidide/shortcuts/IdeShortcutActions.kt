package com.itsaky.androidide.shortcuts

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import org.slf4j.LoggerFactory

/**
 * Executes IDE shortcut actions using the actions registry.
 */
class IdeShortcutActions(
	private val actionDataProvider: () -> ActionData?,
) {
	private val log = LoggerFactory.getLogger(IdeShortcutActions::class.java)

	private val actionsRegistry: ActionsRegistry
		get() = ActionsRegistry.getInstance()

	/**
	 * Executes the shortcut action with the given ID, returning whether it ran.
	 */
	fun execute(actionId: String): Boolean {
		val data = actionDataProvider()
		if (data == null) {
			log.warn("Missing ActionData for shortcut actionId={}", actionId)
			return false
		}

		val registry = actionsRegistry as? DefaultActionsRegistry
		if (registry == null) {
			log.warn("ActionsRegistry is not DefaultActionsRegistry for actionId={}", actionId)
			return false
		}

		val action = findActionById(actionsRegistry, actionId)
		if (action == null) {
			log.warn("No action found for shortcut actionId={}", actionId)
			return false
		}

		action.prepare(data)
		if (!action.enabled) {
			log.debug("Shortcut action is disabled actionId={}", actionId)
			return false
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
}
