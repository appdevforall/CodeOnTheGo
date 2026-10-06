package org.appdevforall.codeonthego.lsp.kotlin.compiler

import org.appdevforall.codeonthego.lsp.kotlin.compiler.modules.KtLibraryModule
import org.appdevforall.codeonthego.lsp.kotlin.compiler.modules.KtModule
import org.appdevforall.codeonthego.lsp.kotlin.compiler.modules.KtSourceModule
import org.appdevforall.codeonthego.lsp.kotlin.compiler.modules.buildKtLibraryModule
import org.appdevforall.codeonthego.lsp.kotlin.compiler.modules.buildKtSourceModule
import org.appdevforall.codeonthego.projects.api.AndroidModule
import org.appdevforall.codeonthego.projects.api.ModuleProject
import org.appdevforall.codeonthego.projects.api.Workspace
import org.appdevforall.codeonthego.projects.models.bootClassPaths
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.pathString

private val logger = LoggerFactory.getLogger("WorkspaceExts")

internal fun Workspace.collectKtModules(
	project: Project,
	appEnv: CoreApplicationEnvironment
): List<KtModule> = buildList {
	fun addModule(module: KtModule) = add(module)

	val moduleProjects = subProjects
		.asSequence()
		.filterIsInstance<ModuleProject>()
		.filter { it.path != rootProject.path }

	val jarToModMap = mutableMapOf<Path, KtLibraryModule>()

	fun addLibrary(path: Path): KtLibraryModule {
		val module = buildKtLibraryModule(project, appEnv) {
			id = path.pathString
			addContentRoot(path)
		}
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

	val subprojectsAsModules = mutableMapOf<ModuleProject, KtSourceModule>()
	val sourceRootToModuleMap = mutableMapOf<Path, KtSourceModule>()

	fun getOrCreateModule(moduleProject: ModuleProject): KtSourceModule {
		subprojectsAsModules[moduleProject]?.let { return it }

		val module = buildKtSourceModule(project) {
			this.module = moduleProject

			bootClassPaths.forEach { addDependency(it) }

			moduleProject.getCompileClasspaths(excludeSourceGeneratedClassPath = true)
				.forEach { classpath ->
					val libDep = libraryDependencies[classpath.toPath()]
					if (libDep == null) {
						logger.error(
							"Skipping non-existent classpath classpath: {}",
							classpath
						)
						return@forEach
					}
					addDependency(libDep)
				}

			moduleProject.getCompileModuleProjects().forEach { dep ->
				addDependency(getOrCreateModule(dep))
			}
		}

		subprojectsAsModules[moduleProject] = module
		module.contentRoots.forEach { root -> sourceRootToModuleMap[root] = module }
		return module
	}

	moduleProjects.forEach { addModule(getOrCreateModule(it)) }
}
