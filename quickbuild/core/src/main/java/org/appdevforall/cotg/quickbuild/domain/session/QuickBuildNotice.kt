package org.appdevforall.cotg.quickbuild.domain.session

/**
 * A message the session needs the host to show, named rather than written.
 *
 * An enum rather than text because the copy lives in the app module's string resources - this
 * module has no `R`. Separate from the session's user-message flow, which the host always
 * flashes as an ERROR: each notice carries its own tone, so a cancellation the user asked for
 * does not read as a failure while a reload that keeps crashing still does.
 */
enum class QuickBuildNotice {
	/** A build the user stopped with the stop button (behaviour 5). */
	BUILD_CANCELLED,

	/**
	 * The proxy app crashed running a deployed generation.
	 *
	 * Always a reload, never an ordinary launch crash: the runtime's crash guard reports only while
	 * a reload is pending. A crash in the user's own new code clears itself, but a payload broken
	 * for a reason no edit reaches is redeployed by every later reload and crashes the same way -
	 * so the copy asks for the fix first and names Restart session for when that does not help.
	 */
	RELOAD_CRASHED,

	/**
	 * A deploy landed by hot swap in an app that has a live service, provider or custom
	 * `Application`, so an instance of one keeps calling the PREVIOUS copies of the helper
	 * classes this build recompiled until it restarts.
	 *
	 * Not a failure: the deploy worked and the recreated activity runs the new code. The restart
	 * closure covers a component's own code and its supertypes, and a hit there restarts the
	 * process; what it cannot see is a helper class the component merely calls.
	 */
	STALE_COMPONENT_HELPERS,

	/**
	 * A save under a test source set (`src/test`, `src/androidTest`, `testFixtures`) was ignored:
	 * nothing there ships in the variant Quick Build deploys, so no build can carry it.
	 *
	 * Not a failure and not something to fix - it says why nothing happened, once per session, so
	 * the silence does not read as a broken watcher. Every later test save is silent, which is the
	 * point: the user only needs to learn this once.
	 */
	TEST_SOURCE_IGNORED,

	/**
	 * aapt2 keeps rejecting the project's resources, so every save fails on that same error.
	 *
	 * The relink links the whole `res/` tree from disk, not the changed set, so an unlinkable
	 * resource blocks the path outright, even for a pure-code save. The copy asks for the fix first
	 * and names Restart session for the case no edit clears - a library resource absent from the
	 * proxy app build's snapshot. Not auto-escalated: aapt2's diagnostics cannot tell the two apart.
	 */
	RELINK_STUCK,

	/**
	 * The proxy app cannot stay alive long enough to receive a payload, so every deploy fails
	 * "not connected" however many times the user relaunches.
	 *
	 * The shape is a baseline that crashes at startup, usually because provisioning ran while the
	 * app's own code was broken. Nothing the user edits reaches it - the fix dexes cleanly and then
	 * has nowhere to land - so only a fresh proxy app build helps. This is therefore the one notice
	 * that asks for Restart session outright, raised by the host as a dialog carrying that action.
	 */
	PROXY_APP_WONT_STAY_UP,
}
