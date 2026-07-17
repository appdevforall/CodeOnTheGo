package com.itsaky.androidide.lsp.kotlin.utils

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.analysis.api.symbols.typeParameters

/** The body every generated stub gets; `TODO` returns `Nothing`, so it type-checks for any return type. */
private const val STUB_BODY = "TODO(\"Not yet implemented\")"

/**
 * The abstract functions and properties [classSymbol] inherits but does not yet implement, in a
 * stable (name-sorted) order. MUST be called inside an `analyze` block.
 *
 * A member's *effective* modality in [classSymbol]'s member scope is [KaSymbolModality.ABSTRACT] only
 * when no supertype (and not the class itself) provides a concrete override. Overridden members show
 * up non-abstract and are excluded. Members inherited unimplemented from several supertypes collapse
 * to a single intersection symbol here, so each signature is rendered once.
 */
internal fun KaSession.membersToImplement(classSymbol: KaClassSymbol): List<KaCallableSymbol> =
	classSymbol.memberScope.callables
		.filter { it.modality == KaSymbolModality.ABSTRACT }
		.filter { it is KaNamedFunctionSymbol || it is KaPropertySymbol }
		.sortedBy { (it as? KaNamedSymbol)?.name?.asString() ?: "" }
		.toList()

/**
 * Renders [member] as a complete `override` declaration, ready to drop into a class body. Every line
 * is prefixed with [indent] (the base indentation of a member in the target class); the body is
 * indented one [unit] deeper. The result has no leading or trailing newline. Returns null for a
 * member this renderer doesn't handle.
 *
 * LSP TextEdits bypass the editor's auto-indent, so the emitted text must already be final: nothing
 * re-indents it after it is applied. [unit] is the surrounding file's own indentation step (a tab or
 * N spaces), so stubs match the file's style rather than assuming tabs.
 */
@OptIn(KaExperimentalApi::class)
internal fun KaSession.renderOverrideStub(
	member: KaCallableSymbol,
	indent: String,
	unit: String,
): String? =
	when (member) {
		is KaNamedFunctionSymbol -> renderFunctionStub(member, indent, unit)
		is KaPropertySymbol -> renderPropertyStub(member, indent, unit)
		else -> null
	}

@OptIn(KaExperimentalApi::class)
private fun KaSession.renderFunctionStub(
	fn: KaNamedFunctionSymbol,
	indent: String,
	unit: String,
): String {
	val modifiers = buildString {
		append(visibilityPrefix(fn.visibility))
		append("override ")
		if (fn.isSuspend) append("suspend ")
		if (fn.isOperator) append("operator ")
		if (fn.isInfix) append("infix ")
	}
	val typeParams = renderTypeParams(fn.typeParameters)
	val receiver = fn.receiverType?.let { "${renderName(it)}." } ?: ""
	val params =
		fn.valueParameters.joinToString(", ") { p ->
			val vararg = if (p.isVararg) "vararg " else ""
			"$vararg${p.name.asString()}: ${renderName(p.returnType)}"
		}
	val returnType = if (fn.returnType.isUnitType) "" else ": ${renderName(fn.returnType)}"
	val where = renderWhereClause(fn.typeParameters)

	return buildString {
		append(indent).append(modifiers).append("fun")
		if (typeParams.isNotEmpty()) append(" ").append(typeParams)
		append(" ").append(receiver).append(fn.name.asString()).append("(").append(params).append(")")
		append(returnType)
		append(where)
		append(" {\n")
		append(indent).append(unit).append(STUB_BODY).append("\n")
		append(indent).append("}")
	}
}

@OptIn(KaExperimentalApi::class)
private fun KaSession.renderPropertyStub(
	prop: KaPropertySymbol,
	indent: String,
	unit: String,
): String {
	val modifiers = "${visibilityPrefix(prop.visibility)}override "
	val keyword = if (prop.isVal) "val" else "var"
	val receiver = prop.receiverType?.let { "${renderName(it)}." } ?: ""
	val type = renderName(prop.returnType)

	return buildString {
		append(indent).append(modifiers).append(keyword).append(" ")
		append(receiver).append(prop.name.asString()).append(": ").append(type).append("\n")
		append(indent).append(unit).append("get() = ").append(STUB_BODY)
		if (!prop.isVal) {
			append("\n").append(indent).append(unit).append("set(value) {}")
		}
	}
}

private fun visibilityPrefix(visibility: KaSymbolVisibility): String =
	when (visibility) {
		KaSymbolVisibility.PROTECTED -> "protected "
		KaSymbolVisibility.INTERNAL -> "internal "
		else -> ""
	}

/** Renders `<A, B : Bound>` (or empty). Multi-bound parameters are emitted via [renderWhereClause]. */
@OptIn(KaExperimentalApi::class)
private fun KaSession.renderTypeParams(typeParams: List<KaTypeParameterSymbol>): String {
	if (typeParams.isEmpty()) return ""
	return typeParams.joinToString(", ", "<", ">") { tp ->
		val bounds = meaningfulBounds(tp)
		if (bounds.size == 1) "${tp.name.asString()} : ${renderName(bounds.single())}" else tp.name.asString()
	}
}

@OptIn(KaExperimentalApi::class)
private fun KaSession.renderWhereClause(typeParams: List<KaTypeParameterSymbol>): String {
	val clauses =
		typeParams.flatMap { tp ->
			val bounds = meaningfulBounds(tp)
			if (bounds.size > 1) bounds.map { "${tp.name.asString()} : ${renderName(it)}" } else emptyList()
		}
	return if (clauses.isEmpty()) "" else " where ${clauses.joinToString(", ")}"
}

/** Upper bounds excluding the implicit `Any?` that an unbounded type parameter carries. */
@OptIn(KaExperimentalApi::class)
private fun KaSession.meaningfulBounds(tp: KaTypeParameterSymbol) = tp.upperBounds.filterNot { renderName(it) == "Any?" }
