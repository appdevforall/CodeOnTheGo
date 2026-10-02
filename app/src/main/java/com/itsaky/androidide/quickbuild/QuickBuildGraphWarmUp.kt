package com.itsaky.androidide.quickbuild

import com.itsaky.androidide.utils.FeatureFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.appdevforall.cotg.quickbuild.service.session.QuickBuildSessionManager
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory

/**
 * Where main-thread code gets the Quick Build session manager from (ADFA-4128).
 *
 * The first Koin resolve builds the whole graph - it reads shared preferences and
 * noBackupFilesDir, and the manager's init block starts a thread and five collectors - so it
 * must run off the main thread, and only one place may be the first: [warmUp], which
 * ProjectHandlerActivity runs on the IO dispatcher at editor start. Everything on the main
 * thread reads [sessionManagerOrNull] - the manager once the warm-up has built it, else null -
 * so no main-thread call can be the one that builds the graph, however early it runs
 * (onTrimMemory has no delay at all; the toolbar's prepare() runs about 150 ms after onStart).
 * A caller that must reach the manager rather than skip when it is not built yet [await]s it.
 *
 * Process-wide like the Koin singleton it fronts: a recreated activity finds it already built.
 * The resolver and the flag are injectable so the ordering is unit-testable without Koin.
 */
class QuickBuildGraphWarmUp internal constructor(
	private val isEnabled: () -> Boolean,
	private val resolve: () -> QuickBuildSessionManager?,
) {
	private val built = MutableStateFlow<QuickBuildSessionManager?>(null)

	/**
	 * The manager once [warmUp] has built it, else null - also null when the feature is off.
	 * Never resolves, so it is safe on the main thread.
	 */
	val sessionManagerOrNull: QuickBuildSessionManager?
		get() = if (isEnabled()) built.value else null

	/**
	 * Resolves the manager, building the graph on the first call. Call off the main thread.
	 * Null when the feature is off or the resolve failed; a failed resolve is not cached, so
	 * the next call retries it.
	 */
	fun warmUp(): QuickBuildSessionManager? {
		if (!isEnabled()) {
			return null
		}
		built.value?.let { return it }
		return resolve()?.also { built.value = it }
	}

	/**
	 * Suspends until [warmUp] has built the manager. Returns null at once when the feature is
	 * off; while a failed resolve is awaiting its retry this waits, bounded by the caller's scope.
	 */
	suspend fun await(): QuickBuildSessionManager? {
		if (!isEnabled()) {
			return null
		}
		return built.filterNotNull().first()
	}

	companion object {
		private val log = LoggerFactory.getLogger("QB-GraphWarmUp")

		/** The one instance production code shares, fronting the Koin singleton. */
		val INSTANCE =
			QuickBuildGraphWarmUp(
				isEnabled = { FeatureFlags.isExperimentsEnabled },
				resolve = {
					runCatching { GlobalContext.get().get<QuickBuildSessionManager>() }
						.onFailure { log.error("Quick Build session manager unavailable", it) }
						.getOrNull()
				},
			)
	}
}
