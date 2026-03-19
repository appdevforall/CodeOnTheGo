package com.itsaky.androidide.shortcuts.groups

import android.content.Context
import android.view.KeyEvent
import com.itsaky.androidide.actions.main.OpenTerminalAction
import com.itsaky.androidide.actions.main.PreferencesAction
import com.itsaky.androidide.actions.sidebar.PreferencesSidebarAction
import com.itsaky.androidide.actions.sidebar.TerminalSidebarAction
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.shortcuts.KeyShortcut
import com.itsaky.androidide.shortcuts.ShortcutCategory
import com.itsaky.androidide.shortcuts.ShortcutContext
import com.itsaky.androidide.shortcuts.ShortcutDefinition

internal class WindowsAndDisplayGroup : ShortcutGroup {
	override fun shortcuts(context: Context): List<ShortcutDefinition> {
		return listOf(
			ShortcutDefinition(
				id = ShortcutDefinitionIds.OPEN_TERMINAL,
				title = context.getString(R.string.shortcut_open_terminal),
				bindings = listOf(
					KeyShortcut.ctrlAlt(KeyEvent.KEYCODE_T),
				),
				category = ShortcutCategory.WINDOWS_AND_DISPLAY,
				contexts = setOf(
					ShortcutContext.EDITOR,
				),
				actionId = TerminalSidebarAction.ID,
			),
			ShortcutDefinition(
				id = ShortcutDefinitionIds.OPEN_TERMINAL_MAIN,
				title = context.getString(R.string.shortcut_open_terminal),
				bindings = listOf(
					KeyShortcut.ctrlAlt(KeyEvent.KEYCODE_T),
				),
				category = ShortcutCategory.WINDOWS_AND_DISPLAY,
				contexts = setOf(
					ShortcutContext.MAIN,
				),
				actionId = OpenTerminalAction.ID,
			),
			ShortcutDefinition(
				id = ShortcutDefinitionIds.OPEN_PREFERENCES,
				title = context.getString(R.string.shortcut_open_preferences),
				bindings = listOf(
					KeyShortcut.ctrl(KeyEvent.KEYCODE_COMMA),
				),
				category = ShortcutCategory.WINDOWS_AND_DISPLAY,
				contexts = setOf(
					ShortcutContext.EDITOR,
				),
				actionId = PreferencesSidebarAction.ID,
			),
			ShortcutDefinition(
				id = ShortcutDefinitionIds.OPEN_PREFERENCES_MAIN,
				title = context.getString(R.string.shortcut_open_preferences),
				bindings = listOf(
					KeyShortcut.ctrl(KeyEvent.KEYCODE_COMMA),
				),
				category = ShortcutCategory.WINDOWS_AND_DISPLAY,
				contexts = setOf(
					ShortcutContext.MAIN,
				),
				actionId = PreferencesAction.ID,
			),
		)
	}
}
