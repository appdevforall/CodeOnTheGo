package com.itsaky.androidide.lsp.java.debug.utils

import com.itsaky.androidide.lsp.debug.model.Source
import com.itsaky.androidide.lsp.java.api.IJavaCompilerSession
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import com.sun.jdi.Location
import org.slf4j.LoggerFactory
import com.itsaky.androidide.lsp.debug.model.Location as LspLocation

private val logger = LoggerFactory.getLogger("ModelUtilsKt")

/**
 * Get the [LspLocation] representation of this [Location].
 *
 * @param useDeclTypeName Whether to the [Location.declaringType] to get the name of the declaring
 * type of this location.
 * @param session The current javac session, or null if the carrier hasn't been loaded yet (e.g.
 * no `.java` file has been touched this session) -- source-path resolution is skipped in that
 * case, same as when no matching source is found.
 */
fun Location.asLspLocation(useDeclTypeName: Boolean = true, session: IJavaCompilerSession?): LspLocation {
	val projectManager = ProjectManagerImpl.getInstance()
	val path =
		session?.let { s ->
			projectManager.workspace
				?.subProjects
				?.filterIsInstance<ModuleProject>()
				?.mapNotNull { moduleProject ->
					var path: String? = null
					if (useDeclTypeName) {
						val className = declaringType().name()
						logger.debug("finding source file for decl class: '{}'", className)
						path = s.findSourceFilePath(moduleProject, className)
					}

					if (path == null) {
						val className =
							this
								.sourcePath()
								.replace('/', '.')
								.substringBeforeLast(".java")
						logger.debug("finding source file for class: '{}'", className)
						path = s.findSourceFilePath(moduleProject, className)
					}

					if (path == null) {
						logger.info("No source found for location: {}", this)
					}

					path
				}?.firstOrNull() // TODO: Maybe allow the user to choose which source file to open?
		}

	val source =
		if (path != null) {
			Source(
				name = path.substringAfterLast('/'),
				path = path,
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
