package com.itsaky.androidide.lsp.java.debug.utils

import com.itsaky.androidide.lsp.debug.model.Source
import com.itsaky.androidide.lsp.java.JavaCompilerProvider
import com.itsaky.androidide.lsp.java.compiler.SourceFileObject
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import com.sun.jdi.Location
import jdkx.tools.JavaFileObject
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.jvm.optionals.getOrNull
import com.itsaky.androidide.lsp.debug.model.Location as LspLocation

private val logger = LoggerFactory.getLogger("ModelUtilsKt")

/**
 * Get the [LspLocation] representation of this [Location].
 *
 * @param useDeclTypeName Whether to the [Location.declaringType] to get the name of the declaring
 * type of this location.
 */
fun Location.asLspLocation(useDeclTypeName: Boolean = true): LspLocation {
	if (declaringType().isKotlinType) {
		return asKotlinLspLocation()
	}

	val projectManager = ProjectManagerImpl.getInstance()
	val fo =
		projectManager.workspace
			?.subProjects
			?.filterIsInstance<ModuleProject>()
			?.mapNotNull { moduleProject ->
				val service = JavaCompilerProvider.get(moduleProject)
				var fo: JavaFileObject? = null
				if (useDeclTypeName) {
					val className = declaringType().name()
					logger.debug("finding source file for decl class: '{}'", className)
					fo = service.findAnywhere(declaringType().name()).getOrNull()
				}

				if (fo == null) {
					val className =
						(this.sourcePathOrNull() ?: "")
							.replace('/', '.')
							.substringBeforeLast(".java")
					logger.debug("finding source file for class: '{}'", className)
					fo = service.findAnywhere(className).getOrNull()
				}

				if (fo != null && (fo.kind != JavaFileObject.Kind.SOURCE || fo !is SourceFileObject)) {
					logger.debug("FileObject {} ({}) is not a source file", fo, fo.javaClass)
					fo = null
				}

				if (fo == null) {
					logger.info("No source found for location: {}", this)
				}

				return@mapNotNull fo as SourceFileObject?
			}?.firstOrNull() // TODO: Maybe allow the user to choose which source file to open?

	val source =
		if (fo != null) {
			Source(
				name = fo.name.substringAfterLast('/'),
				path = fo.name,
			)
		} else {
			Source(
				name = sourceNameOrNull() ?: "",
				path = sourcePathOrNull() ?: "",
			)
		}

	return LspLocation(
		source = source,
		// -1 because we get 1-indexed line numbers from JDI
		// but IDE expects 0-indexed line numbers
		line = lineNumberInSource() - 1,
		column = null,
	)
}

private fun Location.asKotlinLspLocation(): LspLocation {
	val relativePath = sourcePathOrNull()
	val resolved = relativePath?.let(::resolveInSourceRoots)

	if (resolved == null) {
		logger.info("No source found for Kotlin location: {}", this)
	}

	val source =
		if (resolved != null) {
			Source(name = resolved.name, path = resolved.absolutePath)
		} else {
			Source(
				name = sourceNameOrNull() ?: "",
				path = relativePath ?: "",
			)
		}

	return LspLocation(
		source = source,
		line = lineNumberInSource() - 1,
		column = null,
	)
}

/**
 * Resolve a stratum-relative source path to a file on disk.
 *
 * JDI builds that path from the class's package plus its `SourceFile` name, so it only addresses a
 * real file where the directory layout mirrors the package. Kotlin does not require that, so a file
 * under `src/main/kotlin/util/` declaring `package com.example.util` is looked up at
 * `com/example/util/...` and missed. The fallback searches the module's source roots by file name.
 */
private fun resolveInSourceRoots(relativePath: String): File? {
	val modules =
		ProjectManagerImpl
			.getInstance()
			.workspace
			?.subProjects
			?.filterIsInstance<ModuleProject>()
			.orEmpty()

	val byPath =
		modules.firstNotNullOfOrNull { module ->
			module
				.getCompileSourceDirectories()
				.map { dir -> File(dir, relativePath) }
				.firstOrNull(File::isFile)
		}
	if (byPath != null) {
		return byPath
	}

	val fileName = relativePath.substringAfterLast('/')
	return modules.firstNotNullOfOrNull { module ->
		module
			.getCompileSourceDirectories()
			.asSequence()
			.flatMap { dir -> dir.walkTopDown() }
			.firstOrNull { candidate -> candidate.isFile && candidate.name == fileName }
	}
}
