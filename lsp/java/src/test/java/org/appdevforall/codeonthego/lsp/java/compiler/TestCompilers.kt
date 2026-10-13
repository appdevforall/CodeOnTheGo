package org.appdevforall.codeonthego.lsp.java.compiler

import org.appdevforall.codeonthego.projects.api.ModuleProject

/**
 * Creates a compiler for [module] whose classpath lookups answer from [classpath], with the boot
 * classes [bootClasses] and no source classes.
 */
internal fun compilerWithClasspath(
	module: ModuleProject?,
	classpath: ClasspathClassNames?,
	bootClasses: Set<String> = emptySet(),
) = JavaCompilerService(
	module,
	SourceFileManager.NO_MODULE,
	bootClasses,
	ClasspathTypeLookup({ emptyList() }, { classpath }, { bootClasses }),
)
