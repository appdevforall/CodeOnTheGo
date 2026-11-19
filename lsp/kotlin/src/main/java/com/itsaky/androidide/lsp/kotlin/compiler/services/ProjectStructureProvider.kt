package com.itsaky.androidide.lsp.kotlin.compiler.services

import androidx.annotation.VisibleForTesting
import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.NotUnderContentRootModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.backingFilePath
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.analysis.api.projectStructure.copyOrigin
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.originalKtFile
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Paths
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.pathString

internal class ProjectStructureProvider :
	KotlinProjectStructureProviderBase(),
	KtLspService {
	companion object {
		fun getInstance(project: Project): ProjectStructureProvider =
			KotlinProjectStructureProvider.getInstance(project) as ProjectStructureProvider

		/**
		 * Distinct source ids are the library JAR paths plus the source files looked up. A workspace the
		 * size of CodeOnTheGo's own (about 180 dependencies of `:app`, about 2,000 source files) fits, and
		 * an entry holds only two references, so the bound costs well under a megabyte.
		 */
		const val MAX_MEMOIZED_MODULE_LOOKUPS = 4096
	}

	@Volatile
	private var moduleLookups = SourceModuleLookupMemo(MAX_MEMOIZED_MODULE_LOOKUPS)

	@VisibleForTesting
	internal fun memoizedModuleLookupCount(): Int = moduleLookups.size

	private lateinit var modules: List<KtModule>
	private lateinit var project: Project

	private val inMemoryVfToModule = ConcurrentHashMap<VirtualFile, KaModule>()
	private val pathToInMemoryVf = ConcurrentHashMap<String, VirtualFile>()

	fun registerInMemoryFile(
		sourcePath: String,
		vf: VirtualFile,
	) {
		pathToInMemoryVf.remove(sourcePath)?.let { inMemoryVfToModule.remove(it) }

		val module = findModuleForSourceId(sourcePath) ?: return
		inMemoryVfToModule[vf] = module
		pathToInMemoryVf[sourcePath] = vf
	}

	fun unregisterInMemoryFile(sourcePath: String) {
		pathToInMemoryVf.remove(sourcePath)?.let { inMemoryVfToModule.remove(it) }
	}

	fun hasInMemoryFile(sourcePath: String): Boolean = pathToInMemoryVf.containsKey(sourcePath)

	private val notUnderContentRootModuleWithoutPsiFile by lazy {
		NotUnderContentRootModule(
			id = "unnamed-outside-content-root-without-psi",
			moduleDescription = "unnamed-outside-content-root-without-psi",
			project = project,
		)
	}

	override fun setupWith(
		project: MockProject,
		index: KtSymbolIndex,
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>,
	) {
		this.modules = modules
		this.project = project
		// A new memo rather than a cleared one: a lookup still walking the previous graph writes into the
		// memo it started with, which is discarded here.
		moduleLookups = SourceModuleLookupMemo(MAX_MEMOIZED_MODULE_LOOKUPS)
	}

	override fun getModule(
		element: PsiElement,
		useSiteModule: KaModule?,
	): KaModule = getModuleImpl(element, useSiteModule)

	private fun getModuleImpl(
		element: PsiElement,
		useSiteModule: KaModule?,
	): KaModule {
		// A copy in its original's source module makes FIR see every declaration twice; give it a dangling module.
		val containingFile = element.containingFile
		if (containingFile is KtFile && containingFile.copyOrigin != null) {
			computeSpecialModule(containingFile)?.let { return it }
		}

		val virtualFile =
			containingFile?.virtualFile
				?: return notUnderContentRootModuleWithoutPsiFile

		// Fast path: in-memory file registered by onFileContentChanged.
		inMemoryVfToModule[virtualFile]?.let { return it }

		val backingFilePath =
			(element.containingFile as? KtFile)?.let {
				it.backingFilePath ?: it.originalKtFile?.backingFilePath
			}

		if (backingFilePath != null) {
			findModuleForSourceId(backingFilePath.pathString)?.let { return it }
		}

		// If the caller supplies a use-site module, search its dependency tree first.
		// This covers the common case (element is in the same module or one of its direct
		// library dependencies) without scanning every top-level module.
		if (useSiteModule != null) {
			searchVirtualFileInModule(virtualFile, useSiteModule, mutableSetOf())?.let { return it }
		}

		// Full scan: search every top-level module and their transitive dependencies.
		modules.forEach { module ->
			searchVirtualFileInModule(virtualFile, module, mutableSetOf())?.let { return it }
		}

		// Path-based fallback for in-memory LightVirtualFiles created by onFileContentChanged.
		findModuleForSourceId(virtualFile.path)?.let { return it }

		return NotUnderContentRootModule(
			id = "unnamed-outside-content-root-with-psi-$element",
			moduleDescription = "unnamed-outside-content-root module with a PSI file: $element",
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
	 * Completion calls this once per unimported-symbol candidate, so results are memoized, misses
	 * included: a miss is the costliest lookup, a walk of the whole graph. Memoizing is sound because
	 * an environment's module graph is fixed for its lifetime: `AbstractCompilationEnvironment` builds
	 * `modules` once, during initialization; [KtModule.contentRoots] are immutable; and a workspace
	 * change builds a new compiler, whose environment calls [setupWith] again, which starts a new memo.
	 *
	 * @return The declaring [KaModule], or `null` if none is found.
	 */
	fun findModuleForSourceId(sourceId: String): KaModule? = moduleLookups.lookup(sourceId, ::searchModuleForSourceId)

	private fun searchModuleForSourceId(sourceId: String): KaModule? {
		val path = Paths.get(sourceId)
		// By identity, not by moduleDescription: the description is diagnostic text rebuilt on every read,
		// joining every dependency's id, and reading it per visited module per lookup was most of the
		// lookup's cost (ADFA-6855 profiling).
		val visited = Collections.newSetFromMap(IdentityHashMap<KaModule, Boolean>())

		fun search(module: KaModule): KaModule? {
			if (!visited.add(module)) return null
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
	override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule = notUnderContentRootModuleWithoutPsiFile

	private fun searchVirtualFileInModule(
		vf: VirtualFile,
		module: KaModule,
		visited: MutableSet<KaModule>,
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
