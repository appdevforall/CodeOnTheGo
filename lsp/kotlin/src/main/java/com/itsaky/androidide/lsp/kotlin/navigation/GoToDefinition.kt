package com.itsaky.androidide.lsp.kotlin.navigation

import com.itsaky.androidide.lsp.kotlin.utils.rangeOf
import com.itsaky.androidide.lsp.kotlin.utils.toRange
import com.itsaky.androidide.models.Location
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.progress.ICancelChecker
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.containingDeclaration
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.sourcePsiSafe
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("GoToDefinition")

/**
 * Every workspace-source declaration [element] resolves to, as editor [Location]s.
 *
 * Deduplicated by file plus range and ordered by file path then start offset, so a multi-candidate
 * result is stable. Candidates whose declaration is not a workspace source - the stdlib, the
 * framework, any library jar - are dropped: there is no decompiler on device and the editor can
 * only open a real file.
 *
 * Must be called inside an analysis session, while holding the project read lock.
 */
internal fun KaSession.definitionLocations(
	element: KtElement,
	cancelChecker: ICancelChecker,
): List<Location> =
	resolvedLocations(element, cancelChecker)
		.distinctBy { it.file to it.range }
		.sortedWith(compareBy({ it.file.toString() }, { it.range.start.index }))

private fun KaSession.resolvedLocations(
	element: KtElement,
	cancelChecker: ICancelChecker,
): List<Location> {
	val symbols = symbolsAt(element)
	if (symbols.isNotEmpty()) {
		return symbols.mapNotNull {
			cancelChecker.abortIfCancelled()
			locationOf(it)
		}
	}

	// mainReference resolved but named nothing with a KaSymbol: a break/continue/return label names
	// the labelled loop or lambda itself, which the Analysis API does not model as a symbol. Fall
	// back to the reference's raw PSI resolution and build the location from that PSI directly.
	cancelChecker.abortIfCancelled()
	return listOfNotNull(rawReferenceLocation(element))
}

/**
 * The symbols [element] resolves to. Name references answer through [mainReference]; convention
 * references (`a + b`, `a[i]`, `by lazy`, destructuring, `for` loops) have no name to resolve and
 * answer through the resolved call instead.
 *
 * Resolution over broken code throws, and a throw must read as "not found" rather than crash the
 * request, so both paths are guarded.
 */
private fun KaSession.symbolsAt(element: KtElement): List<KaSymbol> =
	runCatching {
		element.mainReference
			?.resolveToSymbols()
			?.toList()
			// A destructuring entry (`val (first, second) = p`) is simultaneously a declaration and
			// a convention reference: resolveToSymbols() legitimately returns both the entry's own
			// local variable symbol and the componentN function it calls. Drop the self-symbol so
			// this reads as R2's "no self-jump" rule rather than a stray extra candidate.
			?.filterNot { it.sourcePsiSafe<PsiElement>() === element }
			?.ifEmpty { null }
			?: listOfNotNull(element.resolveToCall()?.successfulFunctionCallOrNull()?.symbol)
	}.getOrElse {
		logger.debug("Resolution failed for '{}'", element.text, it)
		emptyList()
	}

/** [symbol]'s declaration as a [Location], or null when it is not a workspace source. */
private fun KaSession.locationOf(symbol: KaSymbol): Location? {
	// sourcePsiSafe() is non-null only for SOURCE and JAVA_SOURCE origins. A library symbol has a
	// non-null psi pointing into a class file, so origin - not nullability - is the test here.
	val declaration =
		symbol.sourcePsiSafe<PsiElement>()
			// A symbol the compiler generated rather than the user writing it (an implicit primary
			// constructor, a data class member) has no PSI of its own; its container does.
			?: symbol.containingDeclaration?.sourcePsiSafe<PsiElement>()
			?: return null

	return locationOfPsi(declaration)
}

/**
 * [element]'s reference resolved directly through PSI rather than a [KaSymbol]. A label names the
 * labelled loop or lambda itself, not a declaration, so there is no symbol to go through; the raw
 * resolve still stays inside the current source (labels cannot cross files), so no workspace-source
 * filter is needed here the way [locationOf] needs one for symbols.
 */
private fun rawReferenceLocation(element: KtElement): Location? =
	runCatching { element.mainReference?.resolve() }
		.getOrElse {
			logger.debug("Raw resolution failed for '{}'", element.text, it)
			null
		}?.let(::locationOfPsi)

/** [declaration]'s [Location], or null when it is not a workspace source. */
private fun locationOfPsi(declaration: PsiElement): Location? {
	val target = (declaration as? KtPropertyAccessor)?.property ?: declaration
	val psiFile = target.containingFile ?: return null
	val virtualFile = psiFile.virtualFile ?: return null
	if (virtualFile.fileSystem.protocol != "file") {
		return null
	}
	val path = runCatching { virtualFile.toNioPath() }.getOrNull() ?: return null

	val nameIdentifier = (target as? PsiNameIdentifierOwner)?.nameIdentifier
	val range =
		if (nameIdentifier != null) {
			rangeOf(nameIdentifier, psiFile)
		} else {
			// No name token: an anonymous object, an init block, a primary constructor. Collapse to
			// the declaration's start so the editor puts the caret there without selecting a body.
			val start = target.textRange.startOffset
			TextRange(start, start).toRange(psiFile)
		}

	if (range == Range.NONE) {
		logger.debug("No document for {}; dropping candidate", path)
		return null
	}

	return Location(path, range)
}
