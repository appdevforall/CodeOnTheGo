package com.itsaky.androidide.shortcuts.groups

import android.content.Context
import android.view.KeyEvent
import com.itsaky.androidide.actions.etc.FindInFileAction
import com.itsaky.androidide.actions.etc.FindInProjectAction
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.shortcuts.KeyShortcut
import com.itsaky.androidide.shortcuts.ShortcutCategory
import com.itsaky.androidide.shortcuts.ShortcutContext
import com.itsaky.androidide.shortcuts.ShortcutDefinition

internal class SearchAndReplaceGroup : ShortcutGroup {
	override fun shortcuts(context: Context): List<ShortcutDefinition> {
		return listOf(
			ShortcutDefinition(
				id = ShortcutDefinitionIds.FIND_IN_PROJECT,
				title = context.getString(R.string.menu_find_project),
				bindings = listOf(
					KeyShortcut.ctrlShift(KeyEvent.KEYCODE_F),
				),
				category = ShortcutCategory.SEARCH_AND_REPLACE,
				contexts = setOf(
					ShortcutContext.EDITOR,
				),
				actionId = FindInProjectAction.ID,
			),
			ShortcutDefinition(
				id = ShortcutDefinitionIds.FIND_IN_FILE,
				title = context.getString(R.string.menu_find_file),
				bindings = listOf(
					KeyShortcut.ctrl(KeyEvent.KEYCODE_F),
				),
				category = ShortcutCategory.SEARCH_AND_REPLACE,
				contexts = setOf(
					ShortcutContext.EDITOR,
				),
				actionId = FindInFileAction.ID,
			),
		)
	}
}
