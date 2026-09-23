package com.itsaky.androidide.lsp.java.compiler

import com.itsaky.androidide.projects.api.ModuleProject

/** Creates a compiler for [module] whose classpath lookups answer from [classpath], with no boot or source classes. */
internal fun compilerWithClasspath(
	module: ModuleProject?,
	classpath: ClasspathClassNames?,
) = JavaCompilerService(
	module,
	SourceFileManager.NO_MODULE,
	emptySet(),
	emptySet(),
	ClasspathTypeLookup({ emptyList() }, { classpath }, { emptySet() }),
)
