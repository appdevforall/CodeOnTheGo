package org.appdevforall.cotg.quickbuild.domain

/** Kind of a manifest component the proxy app build recorded (setup.json `components`). */
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
