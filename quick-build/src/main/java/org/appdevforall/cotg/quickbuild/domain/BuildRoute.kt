package org.appdevforall.cotg.quickbuild.domain

/**
 * The cheapest correct path for a coalesced changed-set (plan section 2.3, tier dispatch).
 *
 * Routing errs toward honesty: anything the quick path cannot absorb with certainty is
 * routed to [FullGradleBuild] rather than served potentially stale. The invariant this
 * protects: the test app never silently runs stale code.
 */
sealed interface BuildRoute {
	/** The session baseline is stale; only a real Gradle build can absorb this change. */
	data class FullGradleBuild(
		val reason: InvalidationReason,
	) : BuildRoute

	/** Resources changed, no code: aapt2 relink, reuse cached dex. */
	data object ResourcesOnly : BuildRoute

	/** assets/ only: no compile, no relink — deploy the changed asset bytes. */
	data object AssetsOnly : BuildRoute

	/** Code changed, no resources: incremental compile + incremental dex. */
	data object CodeOnly : BuildRoute

	/** Mixed save: relink AND compile — never serve stale resources beside new code. */
	data object CodeAndResources : BuildRoute

	// Changed assets ride along in the deploy payload on every route; only AssetsOnly
	// means the payload is nothing BUT assets.

	/** Empty known changed-set: nothing to rebuild (a forced tap may still redeploy). */
	data object NoOp : BuildRoute
}

/** Why a quick-build session baseline can no longer absorb edits on the fast path. */
enum class InvalidationReason {
	MANIFEST_CHANGED,
	GRADLE_CONFIG_CHANGED,

	/**
	 * A watched file changed whose packaging semantics the quick path does not implement
	 * (e.g. a java-resource under src/). Falling back keeps the never-stale invariant.
	 */
	UNSUPPORTED_FILE_CHANGED,

	/** A full Gradle build ran outside the session and moved the baseline. */
	EXTERNAL_FULL_BUILD,
}
