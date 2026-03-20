package com.itsaky.androidide.shortcuts

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.EditText

class ShortcutManager(
	context: Context,
) {

	private val catalog: List<ShortcutDefinition> = ShortcutCatalog().all(context)

	private val contextPriority = listOf(
		ShortcutContext.MODAL,
		ShortcutContext.EDITOR,
		ShortcutContext.MAIN,
		ShortcutContext.APP_GLOBAL,
	)

	fun dispatch(
		event: KeyEvent,
		context: ShortcutContext,
		focusView: View?,
		executionContext: ShortcutExecutionContext,
		hasModal: Boolean = false,
	): Boolean {
		if (!shouldHandleShortcuts(event, focusView)) return false

		val activeContexts = if (hasModal) {
			setOf(ShortcutContext.APP_GLOBAL, ShortcutContext.MODAL)
		} else {
			setOf(ShortcutContext.APP_GLOBAL, context)
		}

		val definition = findMatchingShortcut(event, activeContexts) ?: return false
		return executionContext.ideShortcutActions.execute(definition.actionId)
	}

	private fun shouldHandleShortcuts(
		event: KeyEvent,
		focusView: View?,
	): Boolean {
		if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) return true
		return focusView !is EditText
	}

	private fun findMatchingShortcut(
		event: KeyEvent,
		activeContexts: Set<ShortcutContext>,
	): ShortcutDefinition? {
		val matchingDefinitions = catalog.filter { definition ->
			definition.contexts.any(activeContexts::contains) &&
			definition.bindings.any { it.matches(event) }
		}

		return matchingDefinitions.maxByOrNull { definition ->
			definition.contexts
				.filter { it in activeContexts }
				.maxOfOrNull(::priorityScore)
				?: Int.MIN_VALUE
		}
	}

	private fun priorityScore(context: ShortcutContext): Int {
		val index = contextPriority.indexOf(context)
		return if (index == -1) Int.MIN_VALUE else contextPriority.size - index
	}
}
