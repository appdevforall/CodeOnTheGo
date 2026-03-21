package com.itsaky.androidide.shortcuts

import com.itsaky.androidide.shortcuts.groups.FileManagementGroup
import com.itsaky.androidide.shortcuts.groups.ProjectsAndSolutionsGroup
import com.itsaky.androidide.shortcuts.groups.SearchAndReplaceGroup
import com.itsaky.androidide.shortcuts.groups.ShortcutGroup
import com.itsaky.androidide.shortcuts.groups.WindowsAndDisplayGroup

/**
 * Provides the set of available shortcut groups for the IDE.
 */
class ShortcutGroupProvider {
	fun all(): List<ShortcutGroup> = listOf(
		ProjectsAndSolutionsGroup(),
		WindowsAndDisplayGroup(),
		FileManagementGroup(),
		SearchAndReplaceGroup(),
	)
}
