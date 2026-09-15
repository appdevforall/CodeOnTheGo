package com.itsaky.androidide.lsp.java.debug.utils

import com.itsaky.androidide.projects.ProjectManagerImpl
import org.appdevforall.codeonthego.indexing.jvm.KT_SOURCE_FILE_META_INDEX_KEY
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString

private val logger = LoggerFactory.getLogger("KotlinClassNames")

const val KOTLIN_FILE_EXTENSION = "kt"

fun isKotlinSource(path: String): Boolean = path.endsWith(".$KOTLIN_FILE_EXTENSION")

internal fun fileFacadeBinaryName(
	packageFqName: String,
	fileNameWithoutExtension: String,
): String {
	val facade = fileNameWithoutExtension.replaceFirstChar { it.uppercaseChar() } + "Kt"
	return if (packageFqName.isEmpty()) facade else "$packageFqName.$facade"
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
