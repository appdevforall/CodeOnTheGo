package com.itsaky.androidide.quickbuild

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline

/**
 * Carries a Quick Build session's narration to the Build Output pane, independent of the editor
 * activity's lifecycle.
 *
 * Collecting inside the activity's `repeatOnLifecycle(STARTED)` loses builds: one the user
 * backgrounded CoGo to watch narrates into a cancelled collector, and the replay on return arrives
 * as a first emission [quickBuildOutputLines] rightly says nothing about. So the collector lives as
 * long as the session, and lines produced while no pane is bound queue here until one is.
 *
 * @property scope the session-lifetime scope everything is collected and delivered on; confining
 *   every field to it is why the session thread and the main thread need no lock.
 */
class QuickBuildOutputNarrator(
	private val scope: CoroutineScope,
) {
	/** Lines with nowhere to go yet; oldest first. Bounded - see [MAX_PENDING]. */
	private val pending = ArrayDeque<String>()

	private var sink: ((String) -> Unit)? = null

	/**
	 * Set by [reset], cleared by [bind]: the project is closing, and its session's teardown
	 * still narrates after the queue was cleared, so those lines are dropped instead of queued
	 * for the next project's pane.
	 */
	private var discardUntilBound = false

	/**
	 * The closing session's teardown while its narration is still being dropped, null otherwise.
	 *
	 * [discardUntilBound] alone cannot hold that narration back: the next project's activity binds
	 * a pane in `onCreate`, and the closing session's Gradle cancel is fire-and-forget, so its
	 * progress listener keeps producing lines after that bind and they were written to the new
	 * project's pane. A line produced before the old teardown fell quiet belongs to the project
	 * that closed, whatever is bound now.
	 *
	 * A token rather than a flag so a second close's [reset] outranks the first one's completion.
	 */
	private var discardUntilQuiet: Any? = null

	/**
	 * Starts narrating a session's status changes; call once per session manager.
	 *
	 * @param status the session's status stream, collected until [scope] dies.
	 */
	fun attach(status: Flow<QuickBuildStatus>) {
		scope.launch {
			var previous: QuickBuildStatus? = null
			status.collect { current ->
				quickBuildOutputLines(previous, current).forEach(::write)
				previous = current
			}
		}
	}

	/**
	 * Narrates one completed save-to-live loop's stage timings.
	 *
	 * @param timeline the finished loop; renders nothing when it carries no measured stage.
	 */
	fun narrate(timeline: E2eTimeline) {
		scope.launch {
			quickBuildTimingLine(timeline)?.let(::write)
		}
	}

	/**
	 * Narrates one raw output line of a running proxy app build, if it is worth reporting.
	 *
	 * Called per Gradle output line from the tooling API's thread, so the filtering happens here
	 * (cheap, pure) and only the survivors cross onto [scope].
	 *
	 * @param line one raw Gradle output line.
	 */
	fun narrateProxyAppProgress(line: String) {
		val rendered = quickBuildProxyAppProgressLine(line) ?: return
		scope.launch { write(rendered) }
	}

	/**
	 * Narrates a failed full Gradle build, quoting Gradle's own output.
	 *
	 * Separate from [attach]'s status narration because the reason is not in the status: a failed
	 * proxy app build surfaces as a one-line message and the session leaving, while the cause only
	 * ever exists in the build's suppressed output (see [quickBuildProxyAppFailureLines]).
	 *
	 * @param output the internal build's captured Gradle output, oldest line first.
	 */
	fun narrateProxyAppBuildFailure(output: List<String>) {
		scope.launch {
			quickBuildProxyAppFailureLines(output).forEach(::write)
		}
	}

	/**
	 * Points the narration at a pane, flushing whatever accumulated while there was none.
	 *
	 * @param sink appends one line to the pane; must tolerate being called after the activity
	 *   that owns it starts tearing down, since the flush is asynchronous.
	 */
	fun bind(sink: (String) -> Unit) {
		scope.launch {
			this@QuickBuildOutputNarrator.sink = sink
			discardUntilBound = false
			while (pending.isNotEmpty()) {
				sink(pending.removeFirst())
			}
		}
	}

	/**
	 * Stops delivering to a pane; later lines queue for the next [bind].
	 *
	 * @param sink the same instance passed to [bind]. A stale unbind (a destroyed activity
	 *   racing a new one's bind) is ignored, which is why identity is checked.
	 */
	fun unbind(sink: (String) -> Unit) {
		scope.launch {
			if (this@QuickBuildOutputNarrator.sink === sink) {
				this@QuickBuildOutputNarrator.sink = null
			}
		}
	}

	/**
	 * Drops every line still queued for a pane that never came back.
	 *
	 * Called when the project closes: the queue is narration about THAT project, so leaving it
	 * would flush stale progress into the next project's Build Output. Bound sinks are left
	 * alone - a currently-visible pane's contents are not this class's to clear.
	 *
	 * The session torn down alongside the reset narrates its own stop asynchronously, after
	 * this; with no pane bound those lines are dropped rather than queued, until a pane binds.
	 *
	 * @param untilQuiet suspends until that teardown has finished narrating; while it does, lines
	 *   are dropped even once a pane binds, since they belong to the project that closed. Null
	 *   leaves the drop lasting only until the next [bind].
	 */
	fun reset(untilQuiet: (suspend () -> Unit)? = null) {
		scope.launch {
			pending.clear()
			discardUntilBound = true
			if (untilQuiet == null) return@launch
			val token = Any()
			discardUntilQuiet = token
			try {
				// Capped: a teardown that never reports quiet would otherwise silence the pane
				// for the rest of the process, which is worse than the stale lines this drops.
				withTimeoutOrNull(QUIET_TIMEOUT_MS) { untilQuiet() }
			} finally {
				if (discardUntilQuiet === token) {
					discardUntilQuiet = null
				}
			}
		}
	}

	private fun write(line: String) {
		if (discardUntilQuiet != null) {
			return
		}
		val target = sink
		if (target != null) {
			target(line)
			return
		}
		if (discardUntilBound) {
			return
		}
		// A pane that never comes back (the user left the editor) must not grow this forever.
		if (pending.size >= MAX_PENDING) {
			pending.removeFirst()
		}
		pending.addLast(line)
	}

	companion object {
		/** Deep enough for many generations of narration; a long absence drops the oldest. */
		private const val MAX_PENDING = 200

		/**
		 * How long [reset] waits for a teardown to fall quiet before narrating again regardless.
		 * Well past a daemon shutdown plus a scratch-tree removal on a slow phone.
		 */
		private const val QUIET_TIMEOUT_MS = 30_000L
	}
}
