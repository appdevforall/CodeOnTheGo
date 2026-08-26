package com.itsaky.androidide.lsp.kotlin.fixtures

/**
 * One source module in a test environment.
 *
 * @param name Module name, and the key other specs use in [dependsOn].
 * @param dirName Directory created for this module's source root, under the test's temp folder.
 * @param dependsOn Names of other source modules this one depends on. Empty means it sees only
 *   the JDK, the stdlib and any extra jars.
 */
internal data class TestSourceModuleSpec(
	val name: String,
	val dirName: String = name,
	val dependsOn: List<String> = emptyList(),
)
