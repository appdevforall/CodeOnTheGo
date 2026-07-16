package com.itsaky.androidide.lsp.kotlin.utils

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Collects the importable fq-names (and their packages) referenced by [ktFile]'s body. MUST be
 * called inside [analyzeMaybeDangling]. Returns only plain strings, so nothing escapes the analyze
 * lifetime. A reference that fails to resolve doesn't join the used set; instead its short name is
 * recorded in [ImportUsage.unresolvedNames] so its import is kept. Both paths are safe: they lead to
 * keeping an import, never removing a used one.
 */
internal fun KaSession.collectImportUsage(ktFile: KtFile): ImportUsage {
	val usedFqNames = HashSet<String>()
	val usedPackages = HashSet<String>()
	val unresolvedNames = HashSet<String>()

	fun record(symbol: KaSymbol?) {
		val fq = symbol?.importableFqNameString() ?: return
		usedFqNames += fq
		val pkg = fq.substringBeforeLast('.', missingDelimiterValue = "")
		if (pkg.isNotEmpty()) usedPackages += pkg
	}

	fun recordAll(symbols: Collection<KaSymbol>?) {
		symbols?.forEach(::record)
	}

	// 1) Plain name / type references (excluding the import list itself). A null (or thrown)
	// resolution is treated as unresolved and its short name kept, so a used-but-unresolvable
	// reference never drops its import. A non-null, non-importable symbol (local, param) is a
	// clean resolve: it records nothing and is not unresolved.
	ktFile.collectDescendantsOfType<KtNameReferenceExpression>().forEach { ref ->
		if (ref.getParentOfType<KtImportList>(strict = false) != null) return@forEach
		val symbol = runCatching { ref.mainReference.resolveToSymbol() }.getOrNull()
		if (symbol != null) record(symbol) else unresolvedNames += ref.getReferencedName()
	}

	// 1b) Implicit-convention references that carry more than one resolution target and so don't
	// resolve through `resolveToSymbol()` (singular; returns null when ambiguous) but do resolve
	// through `resolveToSymbols()` (plural). Confirmed empirically:
	//  - KtForExpression: resolves to [iterator(), hasNext(), next()] -- iterator is the
	//    user-importable one for a `for (x in foo)` loop.
	//  - KtDestructuringDeclarationEntry (one per destructured variable): resolves to that
	//    variable's own componentN() symbol.
	//  - KtPropertyDelegate: resolves to the delegate's getValue()/setValue() symbol(s).
	// Recording every returned symbol is safe: extra (e.g. stdlib Iterator.next) symbols only ever
	// keep an import, never drop a used one.
	ktFile.collectDescendantsOfType<KtForExpression>().forEach { forExpr ->
		runCatching { recordAll(forExpr.mainReference?.resolveToSymbols()) }
	}
	ktFile.collectDescendantsOfType<KtDestructuringDeclarationEntry>().forEach { entry ->
		runCatching { recordAll(entry.mainReference?.resolveToSymbols()) }
	}
	ktFile.collectDescendantsOfType<KtPropertyDelegate>().forEach { delegate ->
		runCatching { recordAll(delegate.mainReference?.resolveToSymbols()) }
	}

	// 2) Convention / operator call sites (no textual name reference).
	ktFile.collectDescendantsOfType<KtElement>().forEach { element ->
		val isConvention =
			element is KtOperationReferenceExpression ||
				element is KtArrayAccessExpression ||
				element is KtCallExpression ||
				element is KtForExpression ||
				element is KtDestructuringDeclaration ||
				element is KtPropertyDelegate
		if (!isConvention) return@forEach
		runCatching {
			record(element.resolveToCall()?.successfulFunctionCallOrNull()?.symbol)
		}
	}

	return ImportUsage(usedFqNames, usedPackages, unresolvedNames)
}

private fun KaSymbol.importableFqNameString(): String? =
	when (this) {
		// A constructor's own callableId is null, so it must map to its containing class -- the name
		// that's actually imported. Covers `Foo()` calls and `@Foo` annotations (both resolve to the
		// constructor). Must precede the KaCallableSymbol branch, which a constructor also matches.
		is KaConstructorSymbol -> containingClassId?.asSingleFqName()?.asString()
		is KaClassLikeSymbol -> classId?.asSingleFqName()?.asString()
		is KaCallableSymbol -> callableId?.asSingleFqName()?.asString()
		else -> null
	}
