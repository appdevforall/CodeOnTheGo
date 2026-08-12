package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.utils.renderName
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtFile

/**
 * Types are rendered **fully qualified** and only then shortened against what the file can resolve.
 *
 * A short name resolves only when the file imports it or it comes from a default-imported package, and
 * a refactoring that adds imports would be a much larger change -- so qualified is the safe starting
 * point and [shortenTypeText] gives back readability where it provably costs nothing.
 */
@OptIn(KaExperimentalApi::class)
private val QUALIFIED_TYPE_RENDERER = KaTypeRendererForSource.WITH_QUALIFIED_NAMES

/** Packages whose simple names resolve with no import at all on the JVM/Android target. */
private val DEFAULT_IMPORTED_PACKAGES =
	setOf(
		"kotlin",
		"kotlin.annotation",
		"kotlin.collections",
		"kotlin.comparisons",
		"kotlin.io",
		"kotlin.jvm",
		"kotlin.ranges",
		"kotlin.sequences",
		"kotlin.text",
		"java.lang",
	)

/** A dotted run of identifiers -- one qualified name inside rendered type text. */
private val QUALIFIED_NAME = Regex("""[\p{L}_][\p{L}\p{Nd}_]*(?:\.[\p{L}_][\p{L}\p{Nd}_]*)+""")

/**
 * A type that cannot be written out as source -- anonymous, intersection, a resolution error, or a
 * platform type the renderer could not reduce (`List<String!>`, where the `!` is on a type argument).
 * `!` is not Kotlin syntax anywhere, so its presence alone settles it.
 *
 * The `"anonymous"` and `"ERROR"` substring checks are not unambiguous -- a real type named
 * `com.example.AnonymousUser` or `p.ERRORS` would also match. Both fail safe: a false positive only
 * declines the rung instead of emitting a block body that does not compile, so the heuristic is left
 * as-is rather than made precise.
 */
internal fun isUnrenderableTypeText(text: String): Boolean =
	text.isBlank() ||
		text.contains("anonymous") ||
		text.contains("ERROR") ||
		text.contains(" & ") ||
		text.contains('!')

/**
 * One type as source text, fully qualified, or null when it cannot be written out.
 *
 * A platform type is unwrapped to its lower bound first: the renderer prints `String!`, which does not
 * parse. Only the outermost bound is unwrapped, so a `!` on a type argument still reaches
 * [isUnrenderableTypeText].
 *
 * Lets a failure from the renderer itself propagate, so a caller that must tell "the renderer threw"
 * from "the type is unrenderable" can. [renderedTypeTextOrNull] is the catching form most callers want.
 */
@OptIn(KaExperimentalApi::class)
internal fun KaSession.typeTextOrNull(type: KaType): String? =
	renderName((type as? KaFlexibleType)?.lowerBound ?: type, QUALIFIED_TYPE_RENDERER)
		.takeUnless(::isUnrenderableTypeText)

internal fun KaSession.renderedTypeTextOrNull(type: KaType): String? = runCatching { typeTextOrNull(type) }.getOrNull()

/**
 * Replaces each qualified name in [rendered] with its simple name when that name already resolves in
 * the file -- because the file imports it exactly, star-imports its package, or it comes from a
 * default-imported package. Everything else stays qualified: verbose, but it always compiles.
 *
 * Purely textual, so it needs no analysis session and is unit-testable on its own. A nested class
 * (`com.example.Outer.Inner`) is only shortened by an import of the nested name itself; an import of
 * the outer class leaves it alone rather than emitting an unresolvable `Inner`.
 *
 * A star import is trusted only when nothing else in the file imports the same simple name from a
 * different package -- that explicit import would resolve first, so writing the short name here would
 * silently name the wrong type.
 */
internal fun shortenTypeText(
	rendered: String,
	importedNames: Set<String>,
	starImportedPackages: Set<String>,
): String =
	QUALIFIED_NAME.replace(rendered) { match ->
		val qualified = match.value
		val container = qualified.substringBeforeLast('.')
		val simpleName = qualified.substringAfterLast('.')
		val resolvable =
			qualified in importedNames ||
				container in DEFAULT_IMPORTED_PACKAGES ||
				(container in starImportedPackages && importedNames.none { it.endsWith(".$simpleName") })
		if (resolvable) simpleName else qualified
	}

/** The fully qualified names [file] imports by name. Syntactic: no analysis session needed. */
internal fun importedNamesOf(file: KtFile): Set<String> =
	file.importDirectives
		.filterNot { it.isAllUnder }
		.mapNotNullTo(mutableSetOf()) { it.importedFqName?.asString() }

/** The packages [file] star-imports (`import com.example.*`). */
internal fun starImportedPackagesOf(file: KtFile): Set<String> =
	file.importDirectives
		.filter { it.isAllUnder }
		.mapNotNullTo(mutableSetOf()) { it.importedFqName?.asString() }
