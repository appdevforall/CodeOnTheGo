package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.NotUnderContentRootModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.backingFilePath
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.originalKtFile
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtFile
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.pathString

internal class ProjectStructureProvider : KtLspService, KotlinProjectStructureProviderBase() {

	companion object {
		private val logger = LoggerFactory.getLogger(ProjectStructureProvider::class.java)
	}

	private lateinit var modules: List<KtModule>
	private lateinit var project: Project

	private val inMemoryVfToModule = ConcurrentHashMap<VirtualFile, KaModule>()
	private val pathToInMemoryVf = ConcurrentHashMap<String, VirtualFile>()

	fun registerInMemoryFile(sourcePath: String, vf: VirtualFile) {
		pathToInMemoryVf.remove(sourcePath)?.let { inMemoryVfToModule.remove(it) }

		val module = findModuleForSourceId(sourcePath) ?: return
		inMemoryVfToModule[vf] = module
		pathToInMemoryVf[sourcePath] = vf
	}

	fun unregisterInMemoryFile(sourcePath: String) {
		pathToInMemoryVf.remove(sourcePath)?.let { inMemoryVfToModule.remove(it) }
	}

	private val notUnderContentRootModuleWithoutPsiFile by lazy {
		NotUnderContentRootModule(
			id = "unnamed-outside-content-root",
			moduleDescription = "unnamed-outside-content-root",
			project = project,
		)
	}

	override fun setupWith(
		project: MockProject,
		index: KtSymbolIndex,
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>
	) {
		this.modules = modules
		this.project = project
	}

	override fun getModule(
		element: PsiElement,
		useSiteModule: KaModule?
	): KaModule {
		val virtualFile = element.containingFile?.virtualFile
			?: return notUnderContentRootModuleWithoutPsiFile

		// Fast path: in-memory file registered by onFileContentChanged.
		inMemoryVfToModule[virtualFile]?.let { return it }

		val visited = mutableSetOf<KaModule>()

		val backingFilePath = (element.containingFile as? KtFile)?.let {
			it.backingFilePath ?: it.originalKtFile?.backingFilePath
		}

		if (backingFilePath != null) {
			findModuleForSourceId(backingFilePath.pathString)?.let { return it }
		}

		// If the caller supplies a use-site module, search its dependency tree first.
		// This covers the common case (element is in the same module or one of its direct
		// library dependencies) without scanning every top-level module.
		if (useSiteModule != null) {
			searchVirtualFileInModule(virtualFile, useSiteModule, visited)?.let { return it }
		}

		// Full scan: search every top-level module and their transitive dependencies.
		// The shared `visited` set avoids re-visiting what we already searched above,
		// but still reaches modules that are NOT in useSiteModule's dependency tree
		// (e.g. a library module that is a sibling of useSiteModule, not a child of it).
		modules.forEach { module ->
			searchVirtualFileInModule(virtualFile, module, visited)?.let { return it }
		}

		// Path-based fallback for in-memory LightVirtualFiles created by onFileContentChanged.
		findModuleForSourceId(virtualFile.path)?.let { return it }

		return NotUnderContentRootModule(
			id = "unnamed-outside-content-root",
			moduleDescription = "unnamed-outside-content-root module with a PSI file.",
			project = project,
			file = element.containingFile,
		)
	}

	/**
	 * Find the [KaModule] that owns the given [sourceId].
	 *
	 * - For library JARs, [sourceId] is the JAR path — matched against [KtModule.contentRoots] exactly.
	 * - For source files, [sourceId] is the `.kt` file path — matched by checking whether the path
	 *   falls under any source root in [KtModule.contentRoots].
	 *
	 * The search is recursive: if the top-level modules do not match, their transitive dependencies
	 * are checked as well.
	 *
	 * @return The declaring [KaModule], or `null` if none is found.
	 */
	@OptIn(KaExperimentalApi::class)
	fun findModuleForSourceId(sourceId: String): KaModule? {
		val path = Paths.get(sourceId)
		val visited = mutableSetOf<String>()

		fun search(module: KaModule): KaModule? {
			if (!visited.add(module.moduleDescription)) return null
			if (module is KtModule) {
				val roots = module.contentRoots
				if (roots.contains(path) || roots.any { path.startsWith(it) }) return module
			}
			return module.directRegularDependencies.firstNotNullOfOrNull { search(it) }
		}

		return modules.firstNotNullOfOrNull { search(it) }
	}

	override fun getImplementingModules(module: KaModule): List<KaModule> {
		// TODO: needs to be implemented when we want to support KMP
		return emptyList()
	}

	@OptIn(KaPlatformInterface::class)
	override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule {
		return notUnderContentRootModuleWithoutPsiFile
	}

	private fun searchVirtualFileInModule(
		vf: VirtualFile,
		module: KaModule,
		visited: MutableSet<KaModule>
	): KaModule? {
		if (visited.contains(module)) return null
		if (module.contentScope.contains(vf)) return module

		visited.add(module)
		module.directRegularDependencies
			.forEach { dependency ->
				val submodule = searchVirtualFileInModule(vf, dependency, visited)
				if (submodule != null) return submodule
			}

		return null
	}
}