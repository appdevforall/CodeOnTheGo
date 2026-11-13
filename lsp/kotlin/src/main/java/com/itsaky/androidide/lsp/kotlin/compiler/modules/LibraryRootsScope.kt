package com.itsaky.androidide.lsp.kotlin.compiler.modules

import org.jetbrains.kotlin.com.intellij.openapi.module.Module
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope

/**
 * A library scope that holds [roots] and every file below them, decided by walking a file's
 * parents instead of by a per-entry set.
 *
 * A file whose top-most ancestor has the URL of a root also counts, so the entries of a JAR
 * replaced on disk, which hang off a new root file, stay in a scope built from the old root.
 */
internal class LibraryRootsScope(
	project: Project,
	val roots: Set<VirtualFile>,
) : GlobalSearchScope(project) {
	private val rootUrls = roots.mapTo(HashSet()) { it.url }

	override fun contains(file: VirtualFile): Boolean {
		var top = file
		while (true) {
			if (top in roots) return true
			top = top.parent ?: break
		}
		return top.url in rootUrls
	}

	override fun isSearchInModuleContent(aModule: Module): Boolean = false

	override fun isSearchInLibraries(): Boolean = true
}
