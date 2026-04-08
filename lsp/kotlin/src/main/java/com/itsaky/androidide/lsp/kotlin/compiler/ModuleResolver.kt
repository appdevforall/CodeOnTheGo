package com.itsaky.androidide.lsp.kotlin.compiler

import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths

internal class ModuleResolver(
	private val jarMap: Map<Path, KaLibraryModule>,
) {
	companion object {
		private val logger = LoggerFactory.getLogger(ModuleResolver::class.java)
	}

	/**
	 * Find the module that declares the given source ID (JAR, source file, etc.)
	 */
	fun findDeclaringModule(sourceId: String): KaModule? {
		val path = Paths.get(sourceId)
		jarMap[path]?.let { return it }

		return null
	}
}