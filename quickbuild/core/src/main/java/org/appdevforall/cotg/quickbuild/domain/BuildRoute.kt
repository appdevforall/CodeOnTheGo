package org.appdevforall.cotg.quickbuild.domain

/**
 * Which build path a coalesced changed-set takes - the cheapest one that is still correct.
 *
 * Anything the live reload path cannot absorb with certainty routes to [FullGradleBuild], so
 * the proxy app never runs stale code.
 */
sealed interface BuildRoute {
	/**
	 * The session baseline is stale; only a real Gradle build can absorb this change.
	 *
	 * @property reason what invalidated the baseline; the session manager reports it to the user
	 *   and decides whether the fallback rebuilds the proxy app.
	 */
	data class FullGradleBuild(
		val reason: InvalidationReason,
	) : BuildRoute

	/** Resources changed, no code: aapt2 relink, reuse cached dex. */
	data object ResourcesOnly : BuildRoute

	/**
	 * assets/ only: no compile, no relink - deploy the changed asset bytes.
	 *
	 * Changed assets are included in every route's deploy payload; this route only means the
	 * payload carries nothing else.
	 */
	data object AssetsOnly : BuildRoute

	/** Code changed, no resources: incremental compile + incremental dex. */
	data object CodeOnly : BuildRoute

	/** Mixed save: relink AND compile - never serve stale resources beside new code. */
	data object CodeAndResources : BuildRoute

	/** Empty known changed-set: nothing to rebuild (a forced tap may still redeploy). */
	data object NoOp : BuildRoute

	/**
	 * Background warm-up right after provisioning: compile + dex the whole module once so the
	 * daemon pays the compiler warm-up before the user's first save instead of on it.
	 *
	 * Deploys nothing - the proxy app already runs exactly these sources. Never produced by
	 * the classifier; only [LiveReloadOrchestrator.onWarmCompileRequested] constructs it.
	 */
	data object WarmCompile : BuildRoute
}

/** Why a quick-build session baseline can no longer absorb edits on the live reload path. */
enum class InvalidationReason {
	/** `AndroidManifest.xml` changed: components, permissions and the proxy transform all move. */
	MANIFEST_CHANGED,

	/** A Gradle build script, properties file or version catalog changed: the classpath may move. */
	GRADLE_CONFIG_CHANGED,

	/**
	 * A watched file changed whose packaging semantics the live reload path does not implement
	 * (e.g. a java-resource under src/).
	 */
	UNSUPPORTED_FILE_CHANGED,

	/**
	 * A code/resource/asset file changed in another Gradle module. The live reload path
	 * compiles only the app module against a frozen dependency classpath - other modules'
	 * output is baked into the baseline - so a library-module edit needs a full build.
	 */
	NON_APP_MODULE_SOURCE_CHANGED,

	/** A full Gradle build ran outside the session and moved the baseline. */
	EXTERNAL_FULL_BUILD,

	/**
	 * A changed source could have moved annotation-processor (KSP/kapt) output - a Room
	 * entity, a `@Query`, a Hilt module. Only a real Gradle build re-runs the processor. See
	 * `domain/annotations/AnnotationImpact.kt` for which edits provably miss processor input
	 * and so stay on the live reload path.
	 */
	ANNOTATION_PROCESSOR_INPUT_CHANGED,

	/**
	 * The installed baseline predates the component-restart contract (setup.json schema < 2),
	 * so its runtime would hot-swap a restart-requiring deploy and leave a live
	 * service/provider stale. Rebaselining regenerates setup.json and reinstalls.
	 */
	OUTDATED_BASELINE,

	/**
	 * A proxy app rebuild produced a good APK but the OS install prompt was never confirmed.
	 *
	 * The prompt may never even appear: Android defers the PENDING_USER_ACTION broadcast
	 * until CoGo is foregrounded, and the dialog-owning subscriber is EventBus
	 * lifecycle-bound, so the deferred delivery can land before it re-registers. The next
	 * Quick Build tap or return to the foreground re-runs the rebuild and re-prompts. Unlike
	 * the other reasons this one comes from the session manager, not from a file change.
	 */
	INSTALL_NOT_CONFIRMED,
}
