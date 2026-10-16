package com.itsaky.androidide.quickbuild

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import java.util.concurrent.atomic.AtomicLong

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
 *   every field to it is why the session thread and the main thread need no lock. [closes] is the
 *   one exception, and is atomic for it.
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
	 * The closing session's teardown while the narration that names no build of its own is still
	 * being dropped, null otherwise.
	 *
	 * Covers only [attach]'s status lines and [narrate]'s timings: they arrive from the
	 * process-wide session manager and carry no session, so during a project switch there is no
	 * reading of them that says which project they describe. Proxy app build output says so
	 * itself - see [ProxyAppBuildNarration] - and is never held back by this.
	 *
	 * Ends at the closing teardown falling quiet OR at the next session announcing its own full
	 * build, whichever comes first. The announcement is enough because this stream is ordered:
	 * every status the closing session had left to report was delivered before it. Without that
	 * escape a project opened during a slow teardown narrates its first tap into nothing, which
	 * is the more visible half of the trade.
	 *
	 * A token rather than a flag so a second close's [reset] outranks the first one's completion.
	 */
	private var discardUntilQuiet: Any? = null

	/**
	 * How many projects have closed ([reset]), so a [ProxyAppBuildNarration] can tell whether the
	 * build it speaks for still belongs to the project on screen.
	 *
	 * The one field not confined to [scope]: a handle is taken on the build's own thread.
	 */
	private val closes = AtomicLong()

	/**
	 * One proxy app build's claim on the pane, taken before the build starts.
	 *
	 * The cancel of a closing project's build is fire-and-forget, so its Gradle progress listener
	 * keeps firing - and its failure is reported - after the next project's activity has bound its
	 * pane in `onCreate`. Those lines used to be written to the new project's Build Output. Naming
	 * the build makes that decidable rather than a matter of timing: everything a build started
	 * before the close still says is dropped, for as long as it says it, while the build the
	 * project on screen started narrates normally throughout.
	 */
	inner class ProxyAppBuildNarration internal constructor(
		private val closesAtStart: Long,
	) {
		/**
		 * Narrates one raw output line of this build, if it is worth reporting.
		 *
		 * Called per Gradle output line from the tooling API's thread, so the filtering happens
		 * here (cheap, pure) and only the survivors cross onto [scope].
		 *
		 * @param line one raw Gradle output line.
		 */
		fun progress(line: String) {
			val rendered = quickBuildProxyAppProgressLine(line) ?: return
			scope.launch {
				if (!isStale()) {
					deliver(rendered)
				}
			}
		}

		/**
		 * Narrates this build's failure, quoting Gradle's own output.
		 *
		 * Separate from [attach]'s status narration because the reason is not in the status: a
		 * failed proxy app build surfaces as a one-line message and the session leaving, while the
		 * cause only ever exists in the build's suppressed output (see
		 * [quickBuildProxyAppFailureLines]).
		 *
		 * @param output the internal build's captured Gradle output, oldest line first.
		 */
		fun failure(output: List<String>) {
			scope.launch {
				if (!isStale()) {
					quickBuildProxyAppFailureLines(output).forEach(::deliver)
				}
			}
		}

		/** Whether the project that started this build has closed since. Call on [scope]. */
		private fun isStale(): Boolean = closesAtStart != closes.get()
	}

	/**
	 * Starts narrating a session's status changes; call once per session manager.
	 *
	 * @param status the session's status stream, collected until [scope] dies.
	 */
	fun attach(status: Flow<QuickBuildStatus>) {
		scope.launch {
			var previous: QuickBuildStatus? = null
			status.collect { current ->
				val last = previous
				if (last != null && quickBuildTransition(last, current) is QuickBuildTransition.ProvisioningStarted) {
					// A full build starting is a session announcing itself, which the one being
					// torn down cannot do - so the closing project has nothing left to say on
					// this stream. Cleared before the write so the announcement is itself
					// narrated: it is the first thing the new project says.
					discardUntilQuiet = null
				}
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
	 * Opens the narration for one proxy app build; call once, before the build starts.
	 *
	 * @return the handle that build narrates through, tied to the project open right now.
	 */
	fun proxyAppBuildNarration(): ProxyAppBuildNarration = ProxyAppBuildNarration(closes.get())

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
	 * Every [ProxyAppBuildNarration] taken before this runs is retired here, so the closing
	 * project's build cannot narrate into the next project's pane however long its cancel takes.
	 *
	 * @param untilQuiet suspends until that teardown has finished narrating; while it does, the
	 *   status and timing lines - the ones no build speaks for - are dropped even once a pane
	 *   binds, unless a new session announces itself first ([discardUntilQuiet]). Null leaves the
	 *   drop lasting only until the next [bind].
	 */
	fun reset(untilQuiet: (suspend () -> Unit)? = null) {
		// Before the hop onto [scope]: a line already launched by the closing project's build is
		// queued behind this on [scope] and has to see the bump, or it lands in the next pane.
		closes.incrementAndGet()
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

	/** Delivers one line that no build speaks for, so the closing project's drop applies to it. */
	private fun write(line: String) {
		if (discardUntilQuiet != null) {
			return
		}
		deliver(line)
	}

	/** Puts one line in the bound pane, or in the queue for the next one. Call on [scope]. */
	private fun deliver(line: String) {
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
