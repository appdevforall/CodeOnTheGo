package org.appdevforall.codeonthego.shortcuts.groups

import android.content.Context
import android.view.KeyEvent
import org.appdevforall.codeonthego.actions.main.OpenTerminalAction
import org.appdevforall.codeonthego.actions.main.PreferencesAction
import org.appdevforall.codeonthego.actions.sidebar.PreferencesSidebarAction
import org.appdevforall.codeonthego.actions.sidebar.TerminalSidebarAction
import com.itsaky.androidide.resources.R
import org.appdevforall.codeonthego.shortcuts.KeyShortcut
import org.appdevforall.codeonthego.shortcuts.ShortcutCategory
import org.appdevforall.codeonthego.shortcuts.ShortcutContext
import org.appdevforall.codeonthego.shortcuts.ShortcutDefinition

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
