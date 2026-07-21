package org.appdevforall.cotg.quickbuild.domain

/**
 * What a successful code-bearing quick build should do to the test app.
 *
 * Loader-swap + activity recreate cannot update a live Service/ContentProvider/custom
 * Application instance, so a deploy touching one must restart the test-app process
 * instead (the design contract in quick-build/docs/component-proxying-design.md,
 * section 4). A restart is never-stale-safe because a killed-and-relaunched test app
 * boots the newest persisted generation and binder catch-up reconciles the rest.
 */
sealed interface DeployDecision {
	/** Hot swap + activity recreate - today's path. */
	data object Recreate : DeployDecision

	/** The recompiled set hit the restart closure of [componentClass] (a [kind]). */
	data class Restart(
		val kind: ComponentKind,
		val componentClass: String,
	) : DeployDecision

	/**
	 * The installed baseline cannot take this deploy safely (it predates the component
	 * metadata, so its runtime would ignore a restart request and hot-swap = stale).
	 * The session must fall back to a full rebaseline, which regenerates the baseline.
	 */
	data class Rebaseline(
		val detail: String,
	) : DeployDecision
}

/**
 * The post-compile restart-vs-recreate decision (design contract section 5). Pure JVM -
 * the decision is deterministic from the recompiled class set, the baseline's component
 * facts, and a supertype index.
 *
 * Restart closure = {service, provider, custom-Application classes} united with their
 * user-side supertypes and the nested classes (`Outer$` prefix) of either. Receivers and
 * activities are deliberately NOT in it: manifest receivers instantiate fresh per
 * delivery through the factory, and activities are handled by recreate.
 *
 * The supertype index is seeded from the baked `supertypes` chains and kept live via
 * [onClassHierarchy] with each build's parsed class headers - that is what catches
 * re-parenting a component between builds.
 */
class DeployPolicy(
	components: List<ComponentInfo>,
	/**
	 * False when the baseline's setup.json predates schema v2 (no `components`): the
	 * restart closure is unknowable AND the installed runtime ignores restart requests,
	 * so every code-bearing deploy must [DeployDecision.Rebaseline] (self-healing: the
	 * rebaseline regenerates a v2 baseline).
	 */
	private val componentInfoAvailable: Boolean = true,
) {
	private val restartComponents = components.filter { it.kind in RESTART_KINDS }

	/** class -> direct supertypes. Seeded from the baked chains, replaced per class by [onClassHierarchy]. */
	private val superEdges = HashMap<String, Set<String>>()

	init {
		// A baked chain [A, B] for component C contributes edges C->A and A->B.
		components.forEach { component ->
			var subclass = component.className
			component.supertypes.forEach { supertype ->
				superEdges.merge(subclass, setOf(supertype), Set<String>::plus)
				subclass = supertype
			}
		}
	}

	/**
	 * Records [className]'s current direct supertypes (superclass + interfaces), parsed
	 * from the class file this build emitted. Replaces the previous edges for the class,
	 * so re-parenting drops the old parent from future closures.
	 */
	fun onClassHierarchy(
		className: String,
		directSupertypes: Collection<String>,
	) {
		superEdges[className] = directSupertypes.toSet()
	}

	/**
	 * Decides for one successful compile.
	 *
	 * @param changedClassFiles the .class paths this compile emitted (relative,
	 *   '/'-or-OS-separated, e.g. `com/example/Foo$Bar.class`). Null = unknown
	 *   recompiled set - decided conservatively (restart when any restart-sensitive
	 *   component exists), because guessing "no hit" could serve a stale service.
	 */
	fun decide(changedClassFiles: Collection<String>?): DeployDecision {
		if (changedClassFiles != null && changedClassFiles.isEmpty()) return DeployDecision.Recreate
		if (!componentInfoAvailable) {
			return DeployDecision.Rebaseline(
				"the installed baseline predates component metadata (setup.json schema v2)",
			)
		}
		if (changedClassFiles == null) {
			val component = restartComponents.firstOrNull() ?: return DeployDecision.Recreate
			return DeployDecision.Restart(component.kind, component.className)
		}

		val changedFqns = changedClassFiles.map(::pathToFqn)
		restartComponents.forEach { component ->
			val closure = closureOf(component.className)
			val hit =
				changedFqns.any { fqn ->
					fqn in closure || closure.any { member -> fqn.startsWith(member + "\$") }
				}
			if (hit) return DeployDecision.Restart(component.kind, component.className)
		}
		return DeployDecision.Recreate
	}

	/** The component class plus its transitive supertypes; cycle-guarded. */
	private fun closureOf(componentClass: String): Set<String> {
		val closure = LinkedHashSet<String>()
		val queue = ArrayDeque(listOf(componentClass))
		while (queue.isNotEmpty()) {
			val next = queue.removeFirst()
			if (closure.add(next)) {
				superEdges[next]?.let(queue::addAll)
			}
		}
		return closure
	}

	private companion object {
		private val RESTART_KINDS =
			setOf(ComponentKind.SERVICE, ComponentKind.PROVIDER, ComponentKind.APPLICATION)

		/** `com/example/Foo$Bar.class` -> `com.example.Foo$Bar` (backslashes tolerated). */
		private fun pathToFqn(path: String): String =
			path
				.removeSuffix(".class")
				.replace('\\', '/')
				.replace('/', '.')
	}
}
