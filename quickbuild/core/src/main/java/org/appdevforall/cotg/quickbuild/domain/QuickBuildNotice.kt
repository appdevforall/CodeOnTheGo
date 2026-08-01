package org.appdevforall.cotg.quickbuild.domain

/**
 * A neutral, non-error thing the session needs to tell the user about. Distinct from the
 * session's user-message flow, which the host flashes as an ERROR: a cancellation the user
 * asked for is not a failure and must not read as one.
 *
 * An enum rather than text because the copy lives in the app module's string resources -
 * this module has no `R`, and a hard-coded English string here would be untranslatable.
 */
enum class QuickBuildNotice {
	/** A build the user stopped with the stop button (Bryan's behaviour 5). */
	BUILD_CANCELLED,
}
