package com.itsaky.androidide.shortcuts.groups

import android.content.Context
import android.view.KeyEvent
import com.itsaky.androidide.actions.main.CloneRepositoryAction
import com.itsaky.androidide.actions.main.CreateProjectAction
import com.itsaky.androidide.actions.main.OpenProjectAction
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.shortcuts.KeyShortcut
import com.itsaky.androidide.shortcuts.ShortcutCategory
import com.itsaky.androidide.shortcuts.ShortcutContext
import com.itsaky.androidide.shortcuts.ShortcutDefinition

internal class ProjectsAndSolutionsGroup : ShortcutGroup {
	override fun shortcuts(context: Context): List<ShortcutDefinition> {
		return listOf(
			ShortcutDefinition(
				id = ShortcutDefinitionIds.CREATE_PROJECT,
				title = context.getString(R.string.shortcut_create_project),
				bindings = listOf(
					KeyShortcut.ctrl(KeyEvent.KEYCODE_N),
				),
				category = ShortcutCategory.PROJECTS_AND_SOLUTIONS,
				contexts = setOf(
					ShortcutContext.MAIN,
				),
				actionId = CreateProjectAction.ID,
			),
			ShortcutDefinition(
				id = ShortcutDefinitionIds.OPEN_PROJECT,
				title = context.getString(R.string.shortcut_open_project),
				bindings = listOf(
					KeyShortcut.ctrl(KeyEvent.KEYCODE_O),
				),
				category = ShortcutCategory.PROJECTS_AND_SOLUTIONS,
				contexts = setOf(
					ShortcutContext.MAIN,
				),
				actionId = OpenProjectAction.ID,
			),
			ShortcutDefinition(
				id = ShortcutDefinitionIds.CLONE_REPOSITORY,
				title = context.getString(R.string.shortcut_clone_repository),
				bindings = listOf(
					KeyShortcut.ctrlShift(KeyEvent.KEYCODE_O),
				),
				category = ShortcutCategory.PROJECTS_AND_SOLUTIONS,
				contexts = setOf(
					ShortcutContext.MAIN,
				),
				actionId = CloneRepositoryAction.ID,
			),
		)
	}
}
