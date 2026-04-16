package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.projects.models.bootClassPaths
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.project.structure.builder.KtModuleProviderBuilder
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.slf4j.LoggerFactory

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
class KotlinProjectModel {

	private val logger = LoggerFactory.getLogger(KotlinProjectModel::class.java)

	private var workspace: Workspace? = null
	private var platform: TargetPlatform = JvmPlatforms.defaultJvmPlatform

	private val listeners = mutableListOf<ProjectModelListener>()

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
				.filterIsInstance<ModuleProject>()
				.filter { it.path != workspace.rootProject.path }

			val bootClassPaths = moduleProjects
				.filterIsInstance<AndroidModule>()
				.flatMap { project ->
					project.bootClassPaths
						.filter { it.exists() }
						.map { bootClassPath ->
							addModule(buildKtLibraryModule {
								this.platform = this@KotlinProjectModel.platform
								this.libraryName = bootClassPath.nameWithoutExtension
								addBinaryRoot(bootClassPath.toPath())
							})
						}
				}

			val libraryDependencies = moduleProjects
				.flatMap { it.getCompileClasspaths() }
				.filter { it.exists() }
				.associateWith { library ->
					addModule(buildKtLibraryModule {
						this.platform = this@KotlinProjectModel.platform
						this.libraryName = library.nameWithoutExtension
						addBinaryRoot(library.toPath())
					})
				}

			val subprojectsAsModules = mutableMapOf<ModuleProject, KaSourceModule>()

			fun getOrCreateModule(project: ModuleProject): KaSourceModule {
				subprojectsAsModules[project]?.let { return it }

				val module = buildKtSourceModule {
					this.platform = this@KotlinProjectModel.platform
					this.moduleName = project.name
					addSourceRoots(project.getSourceDirectories().map { it.toPath() })

					bootClassPaths.forEach { addRegularDependency(it) }

					project.getCompileClasspaths(excludeSourceGeneratedClassPath = true)
						.forEach { classpath ->
							val libDep = libraryDependencies[classpath]
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
		}
	}

	private fun notifyListeners(changeKind: ChangeKind) {
		logger.info("Notifying project listeners for change: {}", changeKind)
		listeners.forEach { it.onProjectModelChanged(this, changeKind) }
	}
}