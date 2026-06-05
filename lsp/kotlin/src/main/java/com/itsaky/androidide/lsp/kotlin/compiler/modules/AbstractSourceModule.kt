package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
import org.jetbrains.kotlin.com.intellij.openapi.module.Module
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.walk

internal abstract class AbstractSourceModule(
	project: Project,
	directRegularDependencies: List<KtModule>
) : AbstractKtModule(project, directRegularDependencies) {

	override fun computeFiles(extended: Boolean): Sequence<VirtualFile> =
		contentRoots
			.asSequence()
			.flatMap { it.walk() }
			.filter { !it.isDirectory() && (it.extension == "kt" || it.extension == "java") }
			.mapNotNull {
				project.read {
					VirtualFileManager.getInstance().findFileByNioPath(it)
				}
			}

	/**
	 * Membership is decided by path rather than by a frozen snapshot of
	 * [VirtualFile] instances. This keeps the content scope consistent with
	 * [ProjectStructureProvider.getModule], which resolves files to this module
	 * by path (`findModuleForSourceId`).
	 *
	 * A snapshot-based scope (`filesScope`) goes stale whenever a source file is
	 * created, or its [VirtualFile] instance changes due to a VFS refresh (e.g.
	 * right after a build). The file is then still mapped to this module by path
	 * but is absent from the scope, which makes the Analysis API reject it with
	 * `KaBaseIllegalPsiException` ("element cannot be analyzed in the context of
	 * the current session"). The predicate below mirrors the [computeFiles]
	 * filter and `findModuleForSourceId`, so the two can never disagree.
	 */
	override fun computeBaseContentScope(): GlobalSearchScope =
		object : GlobalSearchScope(project) {
			override fun contains(file: VirtualFile): Boolean {
				if (file.fileSystem.protocol != "file") return false
				val ext = file.extension
				if (ext != "kt" && ext != "java") return false
				val path = runCatching { file.toNioPath() }.getOrNull() ?: return false
				return contentRoots.any { root -> path == root || path.startsWith(root) }
			}

			override fun isSearchInModuleContent(aModule: Module): Boolean = true

			override fun isSearchInLibraries(): Boolean = false
		}
}