package org.appdevforall.cotg.quickbuild.domain.reload

/**
 * Kind of a manifest component the proxy app build recorded (setup.json `components`).
 *
 * The restart closure referred to throughout this file is [DeployPolicy]'s: a
 * restart-sensitive component class plus its user-side supertypes and their nested classes,
 * any recompile of which forces a proxy-app process restart.
 */
enum class ComponentKind {
	/** An `<activity>`; outside the restart closure, since recreate already refreshes it. */
	ACTIVITY,

	/** A `<service>`; a live instance cannot be swapped, so it forces a process restart. */
	SERVICE,

	/** A `<receiver>`; outside the restart closure, being instantiated fresh per delivery. */
	RECEIVER,

	/** A `<provider>`; like a service, a live instance forces a process restart. */
	PROVIDER,

	/** The custom `Application` class; forces a process restart, and has no proxy class. */
	APPLICATION,
}

/**
 * The kinds whose live instance a loader swap cannot update, so a recompile inside their
 * restart closure forces a process restart ([DeployPolicy]).
 *
 * One home for the set, because two rules key off it: the restart decision, and the
 * [org.appdevforall.cotg.quickbuild.domain.session.QuickBuildNotice.STALE_COMPONENT_HELPERS] warning that fires when one of these merely
 * EXISTS and the deploy hot-swapped instead. Both read it through [isRestartSensitive], which
 * also applies the [COGO_INJECTED_COMPONENTS] exemption.
 */
val RESTART_SENSITIVE_KINDS: Set<ComponentKind> =
	setOf(ComponentKind.SERVICE, ComponentKind.PROVIDER, ComponentKind.APPLICATION)

/**
 * The restart-sensitive components CoGo injects into every debuggable app it builds - the
 * logsender AAR's service and the provider that installs it - which the restart rule exempts.
 *
 * Safe because these two classes ship in the BASE APK dex and are absent from every
 * per-generation payload dex, which is exactly the daemon's compile output plus the generated
 * proxy classes. Payload loaders are parent-first with the APK loader as parent, so every
 * generation's proxy resolves the same `Class` object for these supertypes - their identity
 * never changes across a hot swap, and the `ClassCastException` the restart rule exists to
 * prevent cannot arise from them. The proxies themselves hold no state: `ProxySourceGenerator`
 * emits an empty subclass for services and providers.
 *
 * Keyed on the EXACT class name, never a package prefix or a "library-provided" test: the
 * safety comes from these specific classes being absent from the payload, and any library class
 * that DID land in the payload would still be redefined per generation. Same shape, and same
 * reason, as `ComponentProxiabilityResolver.UNPROXIABLE_BY_NAME` in the Gradle plugin.
 */
val COGO_INJECTED_COMPONENTS: Set<String> =
	setOf(
		"com.itsaky.androidide.logsender.LogSenderService",
		"com.itsaky.androidide.logsender.utils.LogSenderInstaller",
	)

/**
 * Whether a code deploy must restart the process because of this component: its kind is one a
 * loader swap cannot update ([RESTART_SENSITIVE_KINDS]) and it is not one CoGo injected
 * ([COGO_INJECTED_COMPONENTS]).
 *
 * The one home for the rule, because both consumers must agree: exempting it in [DeployPolicy]
 * alone would turn every hot swap on an ordinary app into a spurious stale-helpers warning
 * about CoGo's own logsender.
 */
fun ComponentInfo.isRestartSensitive(): Boolean = kind in RESTART_SENSITIVE_KINDS && className !in COGO_INJECTED_COMPONENTS

/**
 * One manifest component recorded by the proxy app build (setup.json `components`, schema v2).
 *
 * Carries only what the deploy policy and restart UX need; intent filters, permissions and
 * the like transfer verbatim in the manifest and are not duplicated here.
 *
 * @property kind which manifest tag declared it, which is what decides restart vs recreate.
 * @property className the USER class FQN declared in the source manifest.
 * @property proxyClass the generated proxy FQN carried in the transformed manifest;
 *   null for the Application entry (nothing addresses it by manifest name).
 * @property launcher true for the launcher activity - its [proxyClass] is the explicit
 *   relaunch target after a restart-deploy.
 * @property supertypes the user-side (project-compiled) superclass chain recorded from
 *   class headers at proxy app build time; seeds the restart closure's supertype index.
 */
data class ComponentInfo(
	val kind: ComponentKind,
	val className: String,
	val proxyClass: String? = null,
	val launcher: Boolean = false,
	val supertypes: List<String> = emptyList(),
)
