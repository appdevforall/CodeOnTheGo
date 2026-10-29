package com.itsaky.androidide.services.builder

import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Which syncs and builds relocated their output (ADFA-4930), from preparation to their
 * `build_completed` metric. Syncs count too: the tooling server reports a sync's completion with
 * the sync's own build ID, like a build's.
 */
internal class RelocatedBuilds {
	private val ids: MutableSet<BuildId> = ConcurrentHashMap.newKeySet()

	/** Records [build] when its Gradle [args] relocate its output. */
	fun prepared(
		build: BuildInfo,
		args: List<String>,
	) {
		if (BuildOutputLocation.isRelocating(args)) ids += build.buildId
	}

	/** Whether the completed build relocated its output; forgets it either way. */
	fun completed(buildId: BuildId): Boolean = ids.remove(buildId)
}
