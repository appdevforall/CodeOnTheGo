package com.itsaky.androidide.lsp.kotlin.compiler.modules

import org.jetbrains.kotlin.com.intellij.openapi.module.Module
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope

/**
 * A library scope that holds [roots] and every file below them, decided by walking a file's
 * parents instead of by a per-entry set.
 */
internal class LibraryRootsScope(
	project: Project,
	val roots: Set<VirtualFile>,
) : GlobalSearchScope(project) {
	override fun contains(file: VirtualFile): Boolean {
		var current: VirtualFile? = file
		while (current != null) {
			if (current in roots) return true
			current = current.parent
		}
		return false
	}

	override fun isSearchInModuleContent(aModule: Module): Boolean = false

	override fun isSearchInLibraries(): Boolean = true
}
