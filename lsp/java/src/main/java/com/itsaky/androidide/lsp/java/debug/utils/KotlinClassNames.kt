package com.itsaky.androidide.lsp.java.debug.utils

import com.itsaky.androidide.projects.ProjectManagerImpl
import org.appdevforall.codeonthego.indexing.jvm.KT_SOURCE_FILE_META_INDEX_KEY
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString

private val logger = LoggerFactory.getLogger("KotlinClassNames")

const val KOTLIN_FILE_EXTENSION = "kt"

/**
 * Whether [path] is a Kotlin source the debuggee will load a class for.
 *
 * `.kts` is deliberately excluded: a script is compiled and run by the build, not packaged into the
 * app, so a breakpoint in one can never bind. `ILanguageServer.supportsDebugging` applies the same
 * rule at the gutter, so the two ends agree on what is debuggable.
 */
fun isKotlinSource(path: String): Boolean = path.endsWith(".$KOTLIN_FILE_EXTENSION")

/**
 * The binary name of the class Kotlin emits for [fileNameWithoutExtension]'s top-level declarations.
 *
 * Best effort, and only an optimisation: these names seed the eager lookup against already-prepared
 * classes, and a name that resolves to nothing costs a miss, not a failure. A breakpoint the eager
 * pass misses still binds through the deferred `ClassPrepare` path, which matches on source name.
 *
 * Two renames are therefore not handled, because neither is recoverable from the index: an explicit
 * `@file:JvmName` and a `@JvmMultifileClass` facade. Pinning those needs `KtFileMetadata` to carry
 * the emitted facade name.
 */
internal fun fileFacadeBinaryName(
	packageFqName: String,
	fileNameWithoutExtension: String,
): String {
	val facade = javaIdentifierFacade(fileNameWithoutExtension) + "Kt"
	return if (packageFqName.isEmpty()) facade else "$packageFqName.$facade"
}

/**
 * Kotlin's `capitalizeAsJavaClassName`: capitalise, and prefix an underscore when the name cannot
 * start a Java identifier, so `2foo.kt` yields `_2fooKt` rather than the invalid `2fooKt`.
 */
private fun javaIdentifierFacade(fileNameWithoutExtension: String): String =
	if (fileNameWithoutExtension.firstOrNull()?.isJavaIdentifierStart() == true) {
		fileNameWithoutExtension.replaceFirstChar { it.uppercaseChar() }
	} else {
		"_$fileNameWithoutExtension"
	}

internal fun classifierBinaryNameOrNull(symbolKey: String): String? {
	if (symbolKey.contains('(') || symbolKey.contains('#')) {
		return null
	}

	if (!symbolKey.contains('/') && symbolKey.contains('.')) {
		return null
	}

	return symbolKey.replace('/', '.')
}

internal fun kotlinBinaryNames(
	packageFqName: String,
	fileNameWithoutExtension: String,
	symbolKeys: List<String>,
): List<String> =
	buildList {
		add(fileFacadeBinaryName(packageFqName, fileNameWithoutExtension))
		symbolKeys.mapNotNullTo(this, ::classifierBinaryNameOrNull)
	}.distinct()

/**
 * Candidate binary names for the classes [file] compiles to: its file facade plus every classifier
 * the Kotlin source index recorded for it.
 *
 * Empty when the index has not seen the file yet, which is not an error - the deferred
 * `ClassPrepare` path binds the breakpoint without these.
 */
suspend fun kotlinBinaryNamesOf(file: Path): List<String> {
	val metaIndex =
		ProjectManagerImpl
			.getInstance()
			.indexingServiceManager
			.registry
			.get(KT_SOURCE_FILE_META_INDEX_KEY)

	if (metaIndex == null) {
		logger.debug("Kotlin file metadata index is not registered")
		return emptyList()
	}

	val metadata = metaIndex.get(file.pathString)
	if (metadata == null) {
		logger.debug("No indexed metadata for Kotlin file {}", file)
		return emptyList()
	}

	return kotlinBinaryNames(
		packageFqName = metadata.packageFqName,
		fileNameWithoutExtension = file.nameWithoutExtension,
		symbolKeys = metadata.symbolKeys,
	)
}
