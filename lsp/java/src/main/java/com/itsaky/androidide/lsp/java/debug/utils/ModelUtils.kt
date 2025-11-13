package com.itsaky.androidide.lsp.java.debug.utils

import com.itsaky.androidide.lsp.debug.model.Source
import com.itsaky.androidide.lsp.java.JavaCompilerProvider
import com.itsaky.androidide.lsp.java.compiler.SourceFileObject
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import com.sun.jdi.Location
import jdkx.tools.JavaFileObject
import org.slf4j.LoggerFactory
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
						this
							.sourcePath()
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
				name = sourceName(),
				path = sourcePath(),
			)
		}

	return LspLocation(
		source = source,
		// -1 because we get 1-indexed line numbers from JDI
		// but IDE expects 0-indexed line numbers
		line = lineNumber() - 1,
		column = null,
	)
}
