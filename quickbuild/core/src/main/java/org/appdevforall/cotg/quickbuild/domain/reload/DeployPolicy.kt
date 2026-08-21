package org.appdevforall.cotg.quickbuild.domain.reload

/**
 * What a successful code-bearing quick build should do to the proxy app.
 *
 * A loader swap plus activity recreate cannot update a live Service, ContentProvider or
 * custom Application instance, so an app that declares one must restart the proxy-app process
 * on every code-bearing deploy. Restarting is safe: the relaunched proxy app boots the newest
 * persisted generation and binder catch-up reconciles the rest.
 */
sealed interface DeployDecision {
	/** Hot swap the loader and recreate the activity - the usual path. */
	data object Recreate : DeployDecision

	/**
	 * The app holds [componentClass] (a [kind]) across reloads, so this deploy must restart.
	 *
	 * @property kind what the held component is, so the status surface can name it to the user.
	 * @property componentClass the USER class FQN of a restart-sensitive component the app
	 *   declares; the first one wins, so it names a cause rather than the complete set of them.
	 */
	data class Restart(
		val kind: ComponentKind,
		val componentClass: String,
	) : DeployDecision

	/**
	 * The installed baseline cannot take this deploy safely (it predates the component
	 * metadata, so its runtime would ignore a restart request and hot-swap = stale).
	 * The session must fall back to a full proxy app rebuild, which regenerates the baseline.
	 *
	 * @property detail human-readable cause, carried into the fallback's user-facing message.
	 */
	data class RebuildProxyApp(
		val detail: String,
	) : DeployDecision
}

/**
 * Decides restart vs recreate after a successful compile (see component-proxying-design.md,
 * "Restart vs recreate").
 *
 * The rule is whether the app declares any component whose live instance a loader swap cannot
 * update - a [ComponentKind.SERVICE], [ComponentKind.PROVIDER] or custom
 * [ComponentKind.APPLICATION] ([RESTART_SENSITIVE_KINDS]). If it declares one, every
 * code-bearing deploy restarts the process; if it declares none, every deploy hot swaps.
 * Receivers and activities never count: manifest receivers are instantiated fresh per delivery
 * through the factory, and activities are covered by recreate. Nor do the components CoGo
 * itself injects ([COGO_INJECTED_COMPONENTS]) - they ship in the base APK dex, so no payload
 * ever redefines them; without that exemption every app would restart on every save, since
 * logsender is injected into every debuggable build.
 *
 * The rule deliberately does not look at what the compile touched. Every generation ships the
 * WHOLE user class set - `DexTool.dex` dexes the compiler's output tree, never a delta - so a
 * hot swap re-defines every user class through a fresh loader whatever the edit was. A held
 * Service, ContentProvider or custom `Application` keeps the previous copy, and the first cast
 * across the two throws `ClassCastException: Foo cannot be cast to Foo`. Keying on the
 * recompiled set is what let an activity-only edit crash the app, reproduced on device
 * (spike2-repro-restart-jvmti-2026-08-20.md).
 */
class DeployPolicy(
	/**
	 * The baseline's manifest components as the proxy app build recorded them; only their
	 * [ComponentInfo.kind] and [ComponentInfo.className] are read.
	 */
	components: List<ComponentInfo>,
	/**
	 * False when the baseline's setup.json predates schema v2: the component list is
	 * unknowable and that runtime ignores restart requests, so every code-bearing deploy
	 * returns [DeployDecision.RebuildProxyApp], which regenerates a v2 baseline.
	 */
	private val componentInfoAvailable: Boolean = true,
) {
	/** The declared components a loader swap cannot update; the first one names the cause. */
	private val heldComponent = components.firstOrNull { it.isRestartSensitive() }

	/**
	 * Decides what one successful compile's output requires of the running proxy app.
	 *
	 * @param changedClassFiles the .class paths this compile emitted, or null when the
	 *   recompiled set is unknown. Read only to spot a compile that emitted nothing at all on a
	 *   baseline with no usable component list; the restart rule itself ignores it, because the
	 *   payload is the whole class set either way (see the class doc).
	 * @return restart when the app declares a restart-sensitive component, a proxy app rebuild
	 *   when the baseline is too old to honour one, else recreate.
	 */
	fun decide(changedClassFiles: Collection<String>?): DeployDecision {
		if (componentInfoAvailable) {
			val held = heldComponent ?: return DeployDecision.Recreate
			return DeployDecision.Restart(held.kind, held.className)
		}
		// A compile that emitted nothing deploys nothing that can stale a component, so it is
		// not worth a full proxy app rebuild on an old baseline.
		if (changedClassFiles != null && changedClassFiles.isEmpty()) return DeployDecision.Recreate
		return DeployDecision.RebuildProxyApp(
			"the installed baseline predates component metadata (setup.json schema v2)",
		)
	}
}
