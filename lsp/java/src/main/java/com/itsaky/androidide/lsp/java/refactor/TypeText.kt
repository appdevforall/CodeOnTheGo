package com.itsaky.androidide.lsp.java.refactor

import jdkx.lang.model.type.DeclaredType
import jdkx.lang.model.type.TypeKind
import jdkx.lang.model.type.TypeMirror
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.ImportTree
import openjdk.source.tree.NewClassTree
import openjdk.source.util.TreePath
import openjdk.source.util.Trees

/** The only package whose simple names resolve with no import in Java. */
internal val DEFAULT_IMPORTED_PACKAGES = setOf("java.lang")

/** A dotted run of identifiers -- one qualified name inside rendered type text. */
private val QUALIFIED_NAME = Regex("""[\p{L}_$][\p{L}\p{Nd}_$]*(?:\.[\p{L}_$][\p{L}\p{Nd}_$]*)+""")

/**
 * `TYPEVAR` is deliberately absent: a type variable declared on the enclosing method or class is in
 * scope at the anchor and writes out as its own name.
 */
internal fun isValuelessKind(kind: TypeKind): Boolean =
	when (kind) {
		TypeKind.VOID,
		TypeKind.NONE,
		TypeKind.NULL,
		TypeKind.ERROR,
		TypeKind.OTHER,
		TypeKind.EXECUTABLE,
		TypeKind.PACKAGE,
		-> true

		else -> false
	}

/**
 * javac renders a captured wildcard as `capture#1 of ? extends Foo`, an intersection with `&`, and an
 * unresolvable type as `<any>`; none parse. Heuristic, but it fails safe -- a false positive declines a
 * candidate rather than emitting a declaration that does not compile.
 */
internal fun isUnrenderableTypeText(text: String): Boolean =
	text.isBlank() ||
		text.contains('#') ||
		text.contains('&') ||
		text.contains("capture") ||
		text.contains("<any")

/**
 * The type to write into the declaration, or null to decline the candidate.
 *
 * Taken from the *attributed* tree, which is what makes poly expressions correct: javac has already
 * inferred `ArrayList<String>` for `new ArrayList<>()` in a `List<String>` context, so writing it back
 * reproduces the inference rather than guessing. The two poly forms where that would not hold, a lambda
 * and a method reference, are excluded targets. An anonymous class is declined by construction.
 */
fun declaredTypeTextFor(
	path: TreePath,
	trees: Trees,
	root: CompilationUnitTree,
): String? {
	val leaf = path.leaf
	if (leaf is NewClassTree && leaf.classBody != null) return null

	val type = runCatching { trees.getTypeMirror(path) }.getOrNull() ?: return null
	if (isValuelessKind(type.kind)) return null
	if (isAnonymousDeclared(type)) return null

	val rendered = type.toString()
	if (isUnrenderableTypeText(rendered)) return null

	return shortenTypeText(rendered, importedNamesOf(root), starImportedPackagesOf(root))
}

/** A `DeclaredType` whose element has no simple name is an anonymous class. */
private fun isAnonymousDeclared(type: TypeMirror): Boolean =
	runCatching { type is DeclaredType && type.asElement().simpleName.isEmpty() }.getOrDefault(false)

/**
 * Shortens a qualified name only where the file already resolves the short form. Everything else stays
 * qualified: verbose, but it compiles, and this refactoring adds no imports.
 *
 * A nested class is shortened only by an import of the nested name itself, never of the outer class. A
 * star import is trusted only when nothing else imports the same simple name from a different package,
 * since that explicit import would resolve first and the short name would silently mean the wrong type.
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

/** The fully qualified names [root] imports by name. Syntactic: no compiler queries needed. */
internal fun importedNamesOf(root: CompilationUnitTree): Set<String> =
	root.imports
		.filterNot(ImportTree::isStatic)
		.map { it.qualifiedIdentifier.toString() }
		.filterNot { it.endsWith(".*") }
		.toSet()

/** The packages [root] star-imports (`import java.util.*;`). */
internal fun starImportedPackagesOf(root: CompilationUnitTree): Set<String> =
	root.imports
		.filterNot(ImportTree::isStatic)
		.map { it.qualifiedIdentifier.toString() }
		.filter { it.endsWith(".*") }
		.map { it.removeSuffix(".*") }
		.toSet()
