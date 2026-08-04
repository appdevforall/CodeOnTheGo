package org.appdevforall.cotg.quickbuild.domain

/**
 * A neutral, non-error message the session needs to show the user.
 *
 * Separate from the session's user-message flow, which the host flashes as an ERROR: a
 * cancellation the user asked for must not read as a failure. An enum rather than text
 * because the copy lives in the app module's string resources - this module has no `R`.
 */
enum class QuickBuildNotice {
	/** A build the user stopped with the stop button (behaviour 5). */
	BUILD_CANCELLED,
}
