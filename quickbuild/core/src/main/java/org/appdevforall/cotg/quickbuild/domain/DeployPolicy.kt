package org.appdevforall.cotg.quickbuild.domain

/**
 * What a successful code-bearing quick build should do to the proxy app.
 *
 * A loader swap plus activity recreate cannot update a live Service, ContentProvider or
 * custom Application instance, so a deploy touching one must restart the proxy-app process
 * (component-proxying-design.md section 4). Restarting is safe: the relaunched proxy app boots
 * the newest persisted generation and binder catch-up reconciles the rest.
 */
sealed interface DeployDecision {
	/** Hot swap the loader and recreate the activity - the usual path. */
	data object Recreate : DeployDecision

	/**
	 * The recompiled set hit the restart closure of [componentClass] (a [kind]).
	 *
	 * @property kind what the hit component is, so the status surface can name it to the user.
	 * @property componentClass the USER class FQN of the component whose closure was hit; the
	 *   first match wins, so it names a cause rather than the complete set of them.
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
 * Decides restart vs recreate after a successful compile (design contract section 5).
 *
 * The restart closure is the service, provider and custom-Application classes, plus their
 * user-side supertypes and the nested classes of either. Receivers and activities are
 * deliberately outside it: manifest receivers are instantiated fresh per delivery through the
 * factory, and activities are covered by recreate.
 *
 * Pure JVM: the answer is deterministic from the recompiled class set, the baseline's
 * component facts, and the supertype index.
 */
class DeployPolicy(
	/**
	 * The baseline's manifest components as the proxy app build recorded them; components of a
	 * non-restart kind are kept only for their baked supertype chains.
	 */
	components: List<ComponentInfo>,
	/**
	 * False when the baseline's setup.json predates schema v2: the restart closure is
	 * unknowable and that runtime ignores restart requests, so every code-bearing deploy
	 * returns [DeployDecision.RebuildProxyApp], which regenerates a v2 baseline.
	 */
	private val componentInfoAvailable: Boolean = true,
) {
	private val restartComponents = components.filter { it.kind in RESTART_SENSITIVE_KINDS }

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
	 *
	 * @param className the FQN in dot form, `$`-separated for nested classes.
	 * @param directSupertypes the superclass plus directly implemented interfaces, one level
	 *   only; the closure walk supplies the transitivity.
	 */
	fun onClassHierarchy(
		className: String,
		directSupertypes: Collection<String>,
	) {
		superEdges[className] = directSupertypes.toSet()
	}

	/**
	 * Decides what one successful compile's output requires of the running proxy app.
	 *
	 * @param changedClassFiles the .class paths this compile emitted (relative,
	 *   '/'-or-OS-separated, e.g. `com/example/Foo$Bar.class`). Null means the recompiled set
	 *   is unknown, and is answered conservatively - restart whenever any restart-sensitive
	 *   component exists, since guessing "no hit" could leave a stale service running.
	 * @return what the deploy must do; [DeployDecision.Recreate] for an empty (non-null) set,
	 *   because a compile that emitted nothing cannot have touched a component.
	 */
	fun decide(changedClassFiles: Collection<String>?): DeployDecision {
		if (changedClassFiles != null && changedClassFiles.isEmpty()) return DeployDecision.Recreate
		if (!componentInfoAvailable) {
			return DeployDecision.RebuildProxyApp(
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

	/**
	 * The component class plus its transitive supertypes.
	 *
	 * @param componentClass FQN in dot form of the restart-sensitive component to walk up from.
	 * @return [componentClass] plus every supertype reachable through [superEdges], in
	 *   breadth-first order and cycle-guarded, so a re-parenting loop cannot hang the walk.
	 */
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
		/**
		 * Turns a compiler-emitted class-file path into the FQN the closure is keyed by.
		 *
		 * @param path a relative .class path such as `com/example/Foo$Bar.class`; either
		 *   separator works, so a Windows-style path needs no pre-normalizing.
		 * @return the dot-form FQN, e.g. `com.example.Foo$Bar`, nested classes still
		 *   `$`-separated.
		 */
		private fun pathToFqn(path: String): String =
			path
				.removeSuffix(".class")
				.replace('\\', '/')
				.replace('/', '.')
	}
}
