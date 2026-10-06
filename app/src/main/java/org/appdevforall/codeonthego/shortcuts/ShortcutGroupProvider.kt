package org.appdevforall.codeonthego.shortcuts

import org.appdevforall.codeonthego.shortcuts.groups.FileManagementGroup
import org.appdevforall.codeonthego.shortcuts.groups.ProjectsAndSolutionsGroup
import org.appdevforall.codeonthego.shortcuts.groups.SearchAndReplaceGroup
import org.appdevforall.codeonthego.shortcuts.groups.ShortcutGroup
import org.appdevforall.codeonthego.shortcuts.groups.WindowsAndDisplayGroup

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
