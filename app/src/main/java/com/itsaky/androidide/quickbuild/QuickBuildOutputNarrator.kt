package com.itsaky.androidide.quickbuild

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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

	private fun write(line: String) {
		val target = sink
		if (target != null) {
			target(line)
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
	}
}
