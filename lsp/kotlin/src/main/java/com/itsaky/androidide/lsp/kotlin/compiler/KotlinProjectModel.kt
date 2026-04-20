package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.lsp.kotlin.utils.SymbolVisibilityChecker
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.projects.models.bootClassPaths
import org.appdevforall.codeonthego.indexing.jvm.JVM_LIBRARY_SYMBOL_INDEX
import org.appdevforall.codeonthego.indexing.jvm.JvmLibrarySymbolIndex
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.project.structure.builder.KtModuleProviderBuilder
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/**
 * Holds the project structure derived from a [Workspace].
 *
 * This is the single source of truth for module layout, dependencies,
 * and source roots. It knows nothing about analysis sessions — it just
 * describes *what* the project looks like.
 *
 * When the project structure changes (re-sync) or source files change
 * (build complete), it notifies registered listeners so they can
 * refresh their sessions.
 */
internal class KotlinProjectModel {

	private val logger = LoggerFactory.getLogger(KotlinProjectModel::class.java)

	private var workspace: Workspace? = null
	private var platform: TargetPlatform = JvmPlatforms.defaultJvmPlatform
	private var _moduleResolver: ModuleResolver? = null
	private var _symbolVisibilityChecker: SymbolVisibilityChecker? = null

	private val listeners = mutableListOf<ProjectModelListener>()

	val moduleResolver: ModuleResolver?
		get() = _moduleResolver

	val symbolVisibilityChecker: SymbolVisibilityChecker?
		get() = _symbolVisibilityChecker

	val libraryIndex: JvmLibrarySymbolIndex?
		get() = ProjectManagerImpl.getInstance()
			.indexingServiceManager
			.registry
			.get(JVM_LIBRARY_SYMBOL_INDEX)

	/**
	 * The kind of change that occurred.
	 */
	enum class ChangeKind {
		/** Module structure, dependencies, or platform changed. Full rebuild needed. */
		STRUCTURE,

		/** Only source files within existing roots changed. Incremental refresh possible. */
		SOURCES,
	}

	fun interface ProjectModelListener {
		fun onProjectModelChanged(model: KotlinProjectModel, changeKind: ChangeKind)
	}

	fun addListener(listener: ProjectModelListener) {
		listeners.add(listener)
	}

	fun removeListener(listener: ProjectModelListener) {
		listeners.remove(listener)
	}

	/**
	 * Called when the project is synced (setupWithProject).
	 * This replaces the entire project structure.
	 */
	fun update(workspace: Workspace, platform: TargetPlatform) {
		this.workspace = workspace
		this.platform = platform
		notifyListeners(ChangeKind.STRUCTURE)
	}

	/**
	 * Called when a build completes and source files may have changed
	 * (generated sources added/removed), but the module structure is the same.
	 */
	fun onSourcesChanged() {
		if (workspace == null) {
			logger.warn("onSourcesChanged called before project model was initialized")
			return
		}
		notifyListeners(ChangeKind.SOURCES)
	}

	/**
	 * Configures a [KtModuleProviderBuilder] with the current project structure.
	 *
	 * Called by [CompilationEnvironment] during session creation or rebuild.
	 * This is where the module/dependency graph is constructed — the same logic
	 * currently in [KotlinLanguageServer.recreateSession], but centralized here.
	 */
	fun configureModules(builder: KtModuleProviderBuilder) {
		val workspace = this.workspace
			?: throw IllegalStateException("Project model not initialized")

		builder.apply {
			this.platform = this@KotlinProjectModel.platform

			val moduleProjects = workspace.subProjects
				.asSequence()
				.filterIsInstance<ModuleProject>()
				.filter { it.path != workspace.rootProject.path }

			val jarToModMap = mutableMapOf<Path, KaLibraryModule>()

			fun addLibrary(path: Path): KaLibraryModule {
				val module = addModule(buildKtLibraryModule {
					this.platform = this@KotlinProjectModel.platform
					this.libraryName = path.nameWithoutExtension
					addBinaryRoot(path)
				})
				
				jarToModMap[path] = module
				return module
			}

			val bootClassPaths = moduleProjects
				.filterIsInstance<AndroidModule>()
				.flatMap { project ->
					project.bootClassPaths
						.asSequence()
						.filter { it.exists() }
						.map { it.toPath() }
						.map(::addLibrary)
				}

			val libraryDependencies = moduleProjects
				.flatMap { it.getCompileClasspaths() }
				.filter { it.exists() }
				.map { it.toPath() }
				.associateWith(::addLibrary)

			val subprojectsAsModules = mutableMapOf<ModuleProject, KaSourceModule>()

			fun getOrCreateModule(project: ModuleProject): KaSourceModule {
				subprojectsAsModules[project]?.let { return it }

				val sourceRoots = project.getSourceDirectories().map { it.toPath() }
				val module = buildKtSourceModule {
					this.platform = this@KotlinProjectModel.platform
					this.moduleName = project.name
					addSourceRoots(sourceRoots)

					bootClassPaths.forEach { addRegularDependency(it) }

					project.getCompileClasspaths(excludeSourceGeneratedClassPath = true)
						.forEach { classpath ->
							val libDep = libraryDependencies[classpath.toPath()]
							if (libDep == null) {
								logger.error(
									"Skipping non-existent classpath classpath: {}",
									classpath
								)
								return@forEach
							}
							addRegularDependency(libDep)
						}

					project.getCompileModuleProjects().forEach { dep ->
						addRegularDependency(getOrCreateModule(dep))
					}
				}

				subprojectsAsModules[project] = module
				return module
			}

			moduleProjects.forEach { addModule(getOrCreateModule(it)) }

			val moduleResolver = ModuleResolver(jarMap = jarToModMap)
			_moduleResolver = moduleResolver
			_symbolVisibilityChecker = SymbolVisibilityChecker(moduleResolver)
		}
	}

	private fun notifyListeners(changeKind: ChangeKind) {
		logger.info("Notifying project listeners for change: {}", changeKind)
		listeners.forEach { it.onProjectModelChanged(this, changeKind) }
	}
}