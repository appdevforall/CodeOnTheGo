package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.TextSpan
import jdkx.lang.model.element.Element
import jdkx.lang.model.element.NestingKind
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.element.TypeParameterElement
import jdkx.lang.model.type.ArrayType
import jdkx.lang.model.type.DeclaredType
import jdkx.lang.model.type.TypeMirror
import jdkx.lang.model.type.TypeVariable
import jdkx.lang.model.type.UnionType
import jdkx.lang.model.type.WildcardType
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.util.SourcePositions
import openjdk.source.util.Trees

/** Whether [element] is a type parameter declared on the anchor method itself (R10). */
internal fun isAnchorTypeParameter(
	element: Element,
	anchorMethodElement: Element?,
): Boolean =
	element is TypeParameterElement &&
		anchorMethodElement != null &&
		runCatching { element.genericElement == anchorMethodElement }.getOrDefault(false)

/**
 * Whether [element] is a local or anonymous class the region uses but does not contain. Its name is
 * reachable only from inside the anchor member, so it stops resolving once the body moves.
 */
internal fun isCapturedLocalType(
	element: Element,
	span: TextSpan,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
): Boolean {
	// A reference to the class *itself* is only one of the shapes: `new Helper()` resolves to Helper's
	// constructor and `Helper.CONST` to a field, so a member's own declaring class is asked about too.
	val type =
		element as? TypeElement
			?: element.enclosingElement as? TypeElement
			?: return false
	if (type.nestingKind != NestingKind.LOCAL && type.nestingKind != NestingKind.ANONYMOUS) return false
	val declaration = declarationSpanOf(type, root, trees, positions) ?: return false
	return !span.contains(declaration)
}

/** The local class a reference names, whether directly or through one of its members. */
internal fun localTypeNameOf(element: Element): String {
	val type = element as? TypeElement ?: element.enclosingElement as? TypeElement ?: return element.simpleName.toString()
	return type.simpleName.toString().ifEmpty { element.simpleName.toString() }
}

/**
 * The name of a local or anonymous class appearing anywhere in [type], or null when there is none.
 *
 * A depth limit rather than a visited set: `Enum<E extends Enum<E>>` is a real shape, and comparing
 * `TypeMirror`s for identity is not reliable enough to terminate on.
 */
internal fun localTypeNameIn(
	type: TypeMirror,
	depth: Int = 0,
): String? {
	if (depth > MAX_TYPE_DEPTH) return null
	return when (type) {
		is DeclaredType -> {
			val element = runCatching { type.asElement() }.getOrNull() as? TypeElement
			if (element != null &&
				(element.nestingKind == NestingKind.LOCAL || element.nestingKind == NestingKind.ANONYMOUS)
			) {
				element.simpleName.toString().ifEmpty { "anonymous class" }
			} else {
				type.typeArguments.firstNotNullOfOrNull { localTypeNameIn(it, depth + 1) }
			}
		}

		is ArrayType -> {
			localTypeNameIn(type.componentType, depth + 1)
		}

		is WildcardType -> {
			type.extendsBound?.let { localTypeNameIn(it, depth + 1) }
				?: type.superBound?.let { localTypeNameIn(it, depth + 1) }
		}

		is UnionType -> {
			type.alternatives.firstNotNullOfOrNull { localTypeNameIn(it, depth + 1) }
		}

		else -> {
			null
		}
	}
}

/** The name of a type variable declared on the anchor method appearing anywhere in [type] (R10). */
internal fun anchorTypeVariableIn(
	type: TypeMirror,
	anchorMethodElement: Element?,
	depth: Int = 0,
): String? {
	if (anchorMethodElement == null || depth > MAX_TYPE_DEPTH) return null
	return when (type) {
		is TypeVariable -> {
			val element = runCatching { type.asElement() }.getOrNull()
			if (isAnchorTypeParameter(element ?: return null, anchorMethodElement)) {
				element.simpleName.toString()
			} else {
				null
			}
		}

		is DeclaredType -> {
			type.typeArguments.firstNotNullOfOrNull { anchorTypeVariableIn(it, anchorMethodElement, depth + 1) }
		}

		is ArrayType -> {
			anchorTypeVariableIn(type.componentType, anchorMethodElement, depth + 1)
		}

		is WildcardType -> {
			type.extendsBound?.let { anchorTypeVariableIn(it, anchorMethodElement, depth + 1) }
				?: type.superBound?.let { anchorTypeVariableIn(it, anchorMethodElement, depth + 1) }
		}

		is UnionType -> {
			type.alternatives.firstNotNullOfOrNull { anchorTypeVariableIn(it, anchorMethodElement, depth + 1) }
		}

		else -> {
			null
		}
	}
}

private const val MAX_TYPE_DEPTH = 8

/**
 * Renders a type as source, shortened only where the file already resolves the short form.
 *
 * The import sets are read once per plan rather than per type: every candidate in one plan renders
 * against the same file.
 */
internal class TypeNames(
	root: CompilationUnitTree,
) {
	private val imported = importedNamesOf(root)
	private val starred = starImportedPackagesOf(root)

	fun render(type: TypeMirror): String? {
		val text = runCatching { type.toString() }.getOrNull() ?: return null
		if (isUnrenderableTypeText(text)) return null
		if (isValuelessKind(type.kind)) return null
		return shortenTypeText(text, imported, starred)
	}
}
