package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtLibraryModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtSourceModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.buildKtLibraryModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.buildKtSourceModule
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.pathString

private val logger = LoggerFactory.getLogger("WorkspaceExts")

/** Builds the analysis modules this layout describes, one per source module. */
internal fun KotlinModuleLayout.toKtModules(
	project: Project,
	appEnv: CoreApplicationEnvironment,
): List<KtModule> =
	buildList {
		val jarToModMap = mutableMapOf<Path, KtLibraryModule>()

		// One module per jar: every module shares android.jar, and each KtLibraryModule
		// materializes the jar's full file list for its search scope (ADFA-6381).
		fun addLibrary(path: Path): KtLibraryModule =
			jarToModMap.getOrPut(path) {
				buildKtLibraryModule(project, appEnv) {
					id = path.pathString
					addContentRoot(path)
				}
			}

		val bootLibraries = bootClassPaths.map(::addLibrary)
		val libraryDependencies = libraries.associateWith(::addLibrary)

		val layoutsByPath = modules.associateBy { it.path }
		val sourceModules = mutableMapOf<String, KtSourceModule>()

		fun getOrCreateModule(moduleLayout: SourceModuleLayout): KtSourceModule {
			sourceModules[moduleLayout.path]?.let { return it }

			val module =
				buildKtSourceModule(project) {
					this.layout = moduleLayout

					bootLibraries.forEach { addDependency(it) }

					moduleLayout.classpath.forEach { classpath ->
						val libDep = libraryDependencies[classpath]
						if (libDep == null) {
							logger.error(
								"Skipping non-existent classpath classpath: {}",
								classpath,
							)
							return@forEach
						}
						addDependency(libDep)
					}

					moduleLayout.moduleDependencies
						.mapNotNull { layoutsByPath[it] }
						.forEach { dep -> addDependency(getOrCreateModule(dep)) }
				}

			sourceModules[moduleLayout.path] = module
			return module
		}

		modules.forEach { add(getOrCreateModule(it)) }
	}
