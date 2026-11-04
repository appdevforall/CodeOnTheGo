package com.itsaky.androidide.services.builder

import org.slf4j.LoggerFactory

/**
 * Routes one Gradle output line while an internal build may be suppressing the editor's build
 * UI (see [GradleBuildService.logOutput]): lines go to the editor's listener when it is
 * listening, otherwise into a bounded tail plus the internal progress listener.
 *
 * The tail exists because a suppressed build that FAILS has no other copy of Gradle's reason -
 * the tooling API's own failure is a bare enum. Guarded by the deque itself: written from the
 * tooling API's thread, drained from the caller's.
 *
 * @param maxLines how much tail to keep; oldest lines are dropped beyond it. Gradle puts the
 *   cause at the END of the stream, so a tail is the right shape.
 */
class InternalBuildOutputCapture(
	private val maxLines: Int,
) {
	private val lines = ArrayDeque<String>()

	/**
	 * Routes one Gradle output line.
	 *
	 * @param editorListener the editor's build listener, or null while it is suppressed.
	 * @param progressListener where suppressed lines are additionally reported; it cannot veto
	 *   the capture - a throwing listener is logged and the line is kept.
	 */
	fun onLine(
		line: String,
		editorListener: GradleBuildService.EventListener?,
		progressListener: ((String) -> Unit)?,
	) {
		if (editorListener != null) {
			editorListener.onOutput(line)
			return
		}
		synchronized(lines) {
			if (lines.size >= maxLines) {
				lines.removeFirst()
			}
			lines.addLast(line)
		}
		progressListener?.let { report ->
			try {
				report(line)
			} catch (e: Exception) {
				log.warn("Internal build progress listener threw", e)
			}
		}
	}

	/**
	 * Takes and clears the captured lines, oldest first; empty when nothing was captured.
	 * Draining rather than reading, so one failure's report can never be quoted against the
	 * next build.
	 */
	fun drain(): List<String> =
		synchronized(lines) {
			val captured = lines.toList()
			lines.clear()
			captured
		}

	/** Drops any tail a previous internal build left unread. */
	fun clear() {
		synchronized(lines) { lines.clear() }
	}

	companion object {
		private val log = LoggerFactory.getLogger(InternalBuildOutputCapture::class.java)
	}
}
