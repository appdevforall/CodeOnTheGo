package com.itsaky.androidide.services.builder

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether an INTERNAL build is running - one the user never asked for that goes through the
 * same Gradle path as a Standard Run (today, Quick Build's proxy app build).
 *
 * An acquire that is never released is silent and permanent: the editor's build listener stays
 * suppressed (see [suppressWhileHeld]) so nothing ever clears "a build is running", and the toolbar
 * keeps the Cancel-build label until the process restarts. That is why [hold] is the only way in -
 * a caller cannot put a statement between the acquire and the try.
 *
 * @param onFirstAcquire runs on the OUTERMOST acquire only.
 * @param onHeldChanged runs with true on the outermost acquire and false on the matching release,
 *   so an observer can show a build the user did not start as "a build is running".
 */
class InternalBuildBracket(
	private val onFirstAcquire: () -> Unit = {},
	private val onHeldChanged: (Boolean) -> Unit = {},
) {
	// A counter rather than a boolean, so a nested internal build cannot leave this stuck on.
	private val depth = AtomicInteger(0)

	/** Whether any internal build is running. Read cross-thread; [AtomicInteger] carries the barrier. */
	val isHeld: Boolean
		get() = depth.get() > 0

	/**
	 * Runs [block] with the bracket held, releasing it however [block] leaves - a value, an
	 * exception, or a cancellation. Nothing runs between the acquire and the try.
	 *
	 * [hold] is the only acquire, so the depth can never go negative and needs no clamp.
	 */
	suspend fun <T> hold(block: suspend () -> T): T {
		if (depth.getAndIncrement() == 0) {
			onFirstAcquire()
			notifyHeldChanged(true)
		}
		try {
			return block()
		} finally {
			// The release edge fires from the same finally that drops the depth, so every exit
			// path - value, throw, cancellation - clears the observer's view of the build.
			if (depth.decrementAndGet() == 0) {
				notifyHeldChanged(false)
			}
		}
	}

	/** [value], or null while an internal build is running. */
	fun <T> suppressWhileHeld(value: T?): T? = if (isHeld) null else value

	/**
	 * The observer is a UI hint, so it may not decide whether the block succeeded: a throw from it
	 * would mask the block's own outcome and, on the release edge, strand the observer as held.
	 */
	private fun notifyHeldChanged(held: Boolean) {
		try {
			onHeldChanged(held)
		} catch (err: Throwable) {
			log.error("Internal build listener failed for held={}", held, err)
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(InternalBuildBracket::class.java)
	}
}
