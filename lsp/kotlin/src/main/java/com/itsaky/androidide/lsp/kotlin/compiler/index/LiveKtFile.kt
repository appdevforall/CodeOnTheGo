package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

/**
 * Marks the one door that hands out a live [KtFile] without pinning it.
 *
 * An unpinned instance can be superseded while it is in use, which is what makes FIR report a file as
 * conflicting with itself (ADFA-4165, ADFA-5231). Opting in is only defensible for PSI-only work that
 * opens no analysis session; anything that analyses must use [KtSymbolIndex.withLiveKtFile].
 */
@RequiresOptIn(
	level = RequiresOptIn.Level.ERROR,
	message = "Unpinned live KtFile access. Use KtSymbolIndex.withLiveKtFile unless this is PSI-only work.",
)
@Retention(AnnotationRetention.BINARY)
internal annotation class UnpinnedKtFileAccess

/**
 * Marks the resolution-side door: what the Analysis API service providers answer "what PSI is at this
 * path" with.
 *
 * For an open path it hands back the live instance (the pinned one while a pin is held, otherwise
 * whatever the current-file cache holds), so it is a reference that can be superseded. Analysing what
 * it returns without a pin is exactly what ADFA-3322 did, and it makes FIR see every top-level
 * declaration twice. Opting in is for service providers that only need to name the PSI for a path;
 * anything that analyses must use [KtSymbolIndex.withLiveKtFile].
 *
 * This marker cannot cover the whole door: the Analysis API interfaces the service providers implement
 * re-export the instances through their own public members, which may not carry an opt-in marker
 * (`OPT_IN_MARKER_ON_OVERRIDE`). Those routes are caught at the sink instead, by
 * [com.itsaky.androidide.lsp.kotlin.compiler.modules.UnpinnedAnalysis].
 */
@RequiresOptIn(
	level = RequiresOptIn.Level.ERROR,
	message = "Resolution-side KtFile access. Use KtSymbolIndex.withLiveKtFile for anything that analyses.",
)
@Retention(AnnotationRetention.BINARY)
internal annotation class ResolutionSideKtFileAccess

/**
 * A [KtFile] pinned to its path for the lifetime of the scope that produced it.
 *
 * Every door into the index - the analysis root here, and `getKtFile` as used by the Analysis API
 * service providers - resolves the pinned path to this one instance while the scope is open, so an
 * analysis can never see its own declarations twice.
 *
 * The file is deliberately not exposed as a value: obtain it for the duration of a [read] or
 * [analyzing] block instead. Returning it *directly* from such a block defeats the pin and is rejected.
 * Only that shape is detected - returning it wrapped (in a collection, or as one of its child elements)
 * escapes the check and is just as unsafe.
 *
 * Only [KtSymbolIndex.withLiveKtFile] and [KtSymbolIndex.withLiveKtFileAsync] can produce one.
 */
internal sealed interface LiveKtFile {
	/** The path this instance is pinned to. */
	val path: Path

	/**
	 * True once the open document has moved past the version this instance was parsed from.
	 *
	 * A result computed from a stale instance describes text the user has already replaced; publish it
	 * and the editor shows diagnostics for the wrong content. Always false for a path with no open
	 * document.
	 */
	val isStale: Boolean

	/** Runs [block] with the pinned file under the project read lock. */
	fun <R> read(block: (KtFile) -> R): R

	/**
	 * Analyses [useSite] (the pinned file by default) and runs [block] with the pinned file.
	 *
	 * Holds the project read lock and the global analysis lock at [priority]; [useSite] must be the
	 * pinned file or an element inside it.
	 */
	fun <R> analyzing(
		priority: AnalysisPriority,
		cancelChecker: ScheduledCancelChecker,
		useSite: KtElement? = null,
		block: KaSession.(KtFile) -> R,
	): R

	/**
	 * Analyses a dangling copy of the pinned file whose text is [text], named [name].
	 *
	 * Completion parses a placeholder variant of the buffer; the copy's `originalFile` must point at the
	 * pinned instance or its resolution goes through a file the provider does not know about. Wiring that
	 * up here is why callers never build the copy themselves.
	 */
	fun <R> analyzingVariant(
		name: String,
		text: String,
		priority: AnalysisPriority,
		cancelChecker: ScheduledCancelChecker,
		block: KaSession.(KtFile) -> R,
	): R
}
