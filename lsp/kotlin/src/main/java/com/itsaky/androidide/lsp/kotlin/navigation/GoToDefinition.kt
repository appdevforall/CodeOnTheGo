package com.itsaky.androidide.lsp.kotlin.navigation

import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPreemptedException
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.modules.backingFilePath
import com.itsaky.androidide.lsp.kotlin.compiler.modules.isAnalysisCancellation
import com.itsaky.androidide.lsp.kotlin.compiler.modules.retryingOnPreemption
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.utils.rangeOf
import com.itsaky.androidide.lsp.kotlin.utils.toRange
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DefinitionResult
import com.itsaky.androidide.models.Location
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.progress.ICancelChecker
import kotlinx.coroutines.future.await
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.containingDeclaration
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.sourcePsiSafe
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.originalKtFile
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
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
		// A cancellation is not a resolution failure. CancelCheckerProgressIndicator throws
		// ProcessCanceledException from inside FIR resolution so analysis can abort mid-analyze;
		// swallowing it here would turn a preemption into "definition not found" and leave the
		// coarse cancelChecker calls as the only place cancellation can take effect.
		if (it.isAnalysisCancellation()) throw it
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
 * [element]'s reference resolved directly through PSI rather than a [KaSymbol]. This fallback runs
 * whenever [symbolsAt] came back empty - a label (which names the labelled loop or lambda itself, not
 * a declaration with a symbol) is the common case, but any other empty resolution takes the same path,
 * including one that resolves into a binary. No separate workspace-source filter is needed here: unlike
 * [locationOf], which tests symbol origin, this relies on [locationOfPsi] itself rejecting anything
 * whose containing file has neither a live-document `backingFilePath` (only ever set for a workspace
 * source open in the editor) nor a file-protocol virtual file - a jar-backed target fails both.
 */
private fun rawReferenceLocation(element: KtElement): Location? =
	runCatching { element.mainReference?.resolve() }
		.getOrElse {
			// See the matching comment in symbolsAt: a cancellation must propagate, not be reported
			// as "nothing resolved".
			if (it.isAnalysisCancellation()) throw it
			logger.debug("Raw resolution failed for '{}'", element.text, it)
			null
		}?.let(::locationOfPsi)

/**
 * [declaration]'s [Location], or null when it is not a workspace source.
 *
 * The open-document case is the common one: the file the user is looking at is a live [KtFile] built
 * by `KtSymbolIndex.refreshToCurrent` from the editor buffer, whose `virtualFile` is a non-physical
 * `LightVirtualFile` (protocol `"mock"` in production, null under Robolectric) rather than the on-disk
 * file - so it must be tried through [backingFilePath] first, falling back to the VFS only for
 * declarations reached without going through the live-document cache.
 */
private fun locationOfPsi(declaration: PsiElement): Location? {
	val target = (declaration as? KtPropertyAccessor)?.property ?: declaration
	val psiFile = target.containingFile ?: return null
	val ktFile = psiFile as? KtFile
	val path =
		(ktFile?.backingFilePath ?: ktFile?.originalKtFile?.backingFilePath)
			?: psiFile.virtualFile
				?.takeIf { it.fileSystem.protocol == "file" }
				?.let { runCatching { it.toNioPath() }.getOrNull() }
			?: return null

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

/**
 * Computes the definition result for [params].
 *
 * Mirrors `doSignatureHelp`: the live-PSI await happens outside `project.read`, because the refresh
 * it waits on needs `project.write` and awaiting it under the read lock would deadlock. Every
 * failure short of cancellation collapses to an empty result, which the editor renders as
 * "Definition not found".
 *
 * The context is [AbstractCompilationEnvironment] rather than the concrete `CompilationEnvironment`
 * so the test environment, which subclasses [AbstractCompilationEnvironment] directly, can drive this
 * entry point.
 */
context(env: AbstractCompilationEnvironment)
internal suspend fun findDefinitionAt(params: DefinitionParams): DefinitionResult {
	logger.debug("findDefinitionAt requested for file={} position={}", params.file, params.position)

	if (params.cancelChecker.isCancelled()) {
		logger.debug("Definition request for {} was cancelled before processing", params.file)
		return DefinitionResult.empty()
	}

	return try {
		val offset = params.position.requireIndex()

		// Navigation is a user-invoked command: AnalysisPriority.COMMAND preempts background
		// diagnostics/indexing but yields to keystroke-driven completion, and is never discarded by
		// another command. It can still be preempted by INTERACTIVE, so it retries once (see
		// retryingOnPreemption, and ADR 0011). params.cancelChecker is request-scoped
		// (CancellableRequestParams), so it is the delegate the per-attempt checker wraps.
		val locations =
			retryingOnPreemption(params.cancelChecker, "Definition lookup for ${params.file}") { cancelChecker ->
				// Awaited per attempt, not once: whatever preempted the first attempt also refreshed the
				// live PSI, unregistering the KtFile that attempt held, and analyzing it again would fail.
				//
				// Safe to await a (possibly blocking) refresh here: this runs outside any project.read/write
				// block, so it can't deadlock against the refresh's project.write. Refreshed to the open
				// document's current version, so the caret offset and the PSI it indexes into come from the
				// same text - a stale snapshot points at the wrong element. (params.position is fixed by the
				// request, so a retry after the user typed can still be one edit behind; that resolves to
				// the wrong element or to nothing, never to a crash.)
				val ktFile = env.ktSymbolIndex.getCurrentKtFile(params.file).await()
				if (ktFile == null) {
					logger.warn("File {} cannot be loaded for definition lookup", params.file)
					emptyList()
				} else {
					cancelChecker.abortIfCancelled()
					env.project.read {
						val element = referenceAtCaret(ktFile, offset) ?: return@read emptyList()
						analyzeMaybeDangling(ktFile, AnalysisPriority.COMMAND, cancelChecker) {
							definitionLocations(element, cancelChecker)
						}
					}
				}
			}
		logger.debug("Definition result for {}: {} location(s)", params.file, locations.size)
		DefinitionResult(locations)
	} catch (e: Throwable) {
		if (e.isAnalysisCancellation()) {
			logger.debug(
				"Definition lookup for {} cancelled (preempted={})",
				params.file,
				e is AnalysisPreemptedException,
			)
			return DefinitionResult.empty()
		}
		logger.warn("Definition lookup failed for {}", params.file, e)
		DefinitionResult.empty()
	}
}
