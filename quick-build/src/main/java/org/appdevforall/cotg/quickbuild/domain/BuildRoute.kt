package org.appdevforall.cotg.quickbuild.domain

/**
 * The cheapest correct path for a coalesced changed-set (plan section 2.3, tier dispatch).
 *
 * Routing errs toward honesty: anything the quick path cannot absorb with certainty is
 * routed to [FullGradleBuild] rather than served potentially stale. The invariant this
 * protects: the proxy app never silently runs stale code.
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

	/**
	 * Background warm-up right after provisioning: compile + dex the whole module once so
	 * the daemon pays kotlinc JIT, the classpath-snapshot seed, and the IC-cache build
	 * before the user's first save instead of on it. Deploys nothing - the proxy app
	 * already runs exactly these sources (the proxy app build just produced them), so a
	 * deploy would only restart it for no visible change. Never produced by the
	 * classifier; only [BuildOrchestrator.onSeedRequested] constructs it.
	 */
	data object Seed : BuildRoute
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

	/**
	 * A code/resource/asset file changed OUTSIDE the app module's fast-path source scope -
	 * i.e. in another Gradle module. The quick path incrementally compiles only the app
	 * module against a frozen dependency classpath (that module's compiled output + merged
	 * resources are baked into the proxy app baseline), so it cannot absorb a library-module
	 * source or resource edit. Falling back to a full build keeps the never-stale
	 * invariant; the alternative - the pre-Level-1 behavior - was to not watch other
	 * modules at all, so a library edit fired no event and was SILENTLY not reloaded.
	 */
	NON_APP_MODULE_SOURCE_CHANGED,

	/** A full Gradle build ran outside the session and moved the baseline. */
	EXTERNAL_FULL_BUILD,

	/**
	 * A changed source could have moved annotation-processor (KSP/kapt) output - a Room
	 * entity, a `@Query`, a Hilt module. Only a real Gradle build re-runs the processor,
	 * so the quick path stands down rather than deploy fresh code beside stale generated
	 * classes. Edits that provably miss processor input stay on the fast path; see
	 * `domain/annotations/AnnotationImpact.kt` for what "provably" covers.
	 */
	ANNOTATION_PROCESSOR_INPUT_CHANGED,

	/**
	 * The installed baseline predates the component-restart contract (setup.json
	 * schema < 2, or its runtime ignored a restart request): a restart-requiring
	 * deploy would be silently hot-swapped, leaving a live service/provider stale.
	 * Rebaselining regenerates setup.json and reinstalls the proxy app.
	 */
	OUTDATED_BASELINE,

	/**
	 * A rebaseline built a good APK but its OS install-confirmation was never given -
	 * the dialog was cancelled or left untapped, or (with CoGo backgrounded) never even
	 * shown: Android DEFERS the PENDING_USER_ACTION broadcast until the app is
	 * foregrounded, and the dialog-owning subscriber (InstallationResultHandler) is
	 * EventBus lifecycle-bound (registered onStart, unregistered onStop), so the
	 * deferred delivery can land before it re-registers and nothing launches the
	 * dialog. The baseline is still stale - the session parks in
	 * `Invalidated(awaitingRetry = true)` and the next Quick Build tap OR CoGo's next
	 * return to the foreground (bounded auto-retry) re-runs the rebaseline, which
	 * re-prompts. Unlike the other reasons this one is not detected from a file change;
	 * the session manager raises it itself on `RebaselineOutcome.InstallNotConfirmed`.
	 */
	INSTALL_NOT_CONFIRMED,
}
