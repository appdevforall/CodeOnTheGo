package com.itsaky.androidide.shortcuts.groups

import android.content.Context
import android.view.KeyEvent
import com.itsaky.androidide.actions.file.CloseFileAction
import com.itsaky.androidide.actions.file.SaveFileAction
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.shortcuts.KeyShortcut
import com.itsaky.androidide.shortcuts.ShortcutCategory
import com.itsaky.androidide.shortcuts.ShortcutContext
import com.itsaky.androidide.shortcuts.ShortcutDefinition

internal class FileManagementGroup : ShortcutGroup {
	override fun shortcuts(context: Context): List<ShortcutDefinition> {
		return listOf(
			ShortcutDefinition(
				id = ShortcutDefinitionIds.SAVE_ALL_FILES,
				title = context.getString(R.string.save),
				bindings = listOf(
					KeyShortcut.ctrl(KeyEvent.KEYCODE_S),
				),
				category = ShortcutCategory.FILE_MANAGEMENT,
				contexts = setOf(
					ShortcutContext.EDITOR,
				),
				actionId = SaveFileAction.ID,
			),
			ShortcutDefinition(
				id = ShortcutDefinitionIds.CLOSE_CURRENT_FILE,
				title = context.getString(R.string.shortcut_close_current_file),
				bindings = listOf(
					KeyShortcut.ctrl(KeyEvent.KEYCODE_W),
					KeyShortcut.ctrl(KeyEvent.KEYCODE_F4),
				),
				category = ShortcutCategory.FILE_MANAGEMENT,
				contexts = setOf(
					ShortcutContext.EDITOR,
				),
				actionId = CloseFileAction.ID,
			),
		)
	}
}
