package com.itsaky.androidide.lsp.kotlin.utils

import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * What a file's body actually uses, expressed as plain strings so nothing crosses an `analyze`
 * lifetime boundary. Produced by [collectImportUsage].
 *
 * @property usedFqNames importable fully-qualified names referenced by the body.
 * @property usedPackages parent packages/objects of used symbols (for wildcard matching).
 */
internal data class ImportUsage(
	val usedFqNames: Set<String>,
	val usedPackages: Set<String>,
)

/** JVM packages that Kotlin imports with a wildcard by default; explicit named imports from these are redundant. */
internal val DEFAULT_STAR_PACKAGES: Set<String> = setOf(
	"kotlin",
	"kotlin.annotation",
	"kotlin.collections",
	"kotlin.comparisons",
	"kotlin.io",
	"kotlin.ranges",
	"kotlin.sequences",
	"kotlin.text",
	"kotlin.jvm",
	"java.lang",
)

private val KDOC_LINK = Regex("""\[([^\]\s]+)]""")

/**
 * Computes the canonical import block for [ktFile] given [usage]: unused/redundant imports removed,
 * survivors deduped and lexicographically sorted. Returns null when the imports are already in that
 * exact form (no edit needed). The returned text has no surrounding newlines.
 */
internal fun organizedImportBlock(ktFile: KtFile, usage: ImportUsage): String? {
	val directives = ktFile.importDirectives
	if (directives.isEmpty()) return null

	val filePackage = ktFile.packageFqName.asString()
	val kdocNames = collectKDocLinkNames(ktFile)

	val newLines = directives
		.filter { keepImport(it, usage, filePackage, kdocNames) }
		.mapNotNull { it.importPath?.let { path -> "import $path" } }
		.distinct()
		.sorted()

	val currentLines = directives.mapNotNull { it.importPath?.let { path -> "import $path" } }

	if (newLines == currentLines) return null
	return newLines.joinToString(System.lineSeparator())
}

private fun keepImport(
	directive: KtImportDirective,
	usage: ImportUsage,
	filePackage: String,
	kdocNames: Set<String>,
): Boolean {
	val fqName = directive.importedFqName ?: return true // malformed import -> keep (conservative)
	val fqNameStr = fqName.asString()
	val alias = directive.aliasName
	val shortName = alias ?: fqName.shortName().asString()

	// Conservative: keep anything referenced by short name/alias in a KDoc link.
	if (shortName in kdocNames) return true

	if (directive.isAllUnder) {
		// Wildcard: keep iff some used symbol lives in this package/object.
		return fqNameStr in usage.usedPackages
	}

	val parentPackage = fqName.parent().asString()
	// Redundant named imports (only when not aliased — an alias is meaningful).
	if (alias == null && parentPackage in DEFAULT_STAR_PACKAGES) return false
	if (alias == null && parentPackage == filePackage) return false

	return fqNameStr in usage.usedFqNames
}

private fun collectKDocLinkNames(ktFile: KtFile): Set<String> =
	PsiTreeUtil.collectElementsOfType(ktFile, KDoc::class.java)
		.flatMap { kdoc -> KDOC_LINK.findAll(kdoc.text).map { it.groupValues[1].substringAfterLast('.') } }
		.toSet()
