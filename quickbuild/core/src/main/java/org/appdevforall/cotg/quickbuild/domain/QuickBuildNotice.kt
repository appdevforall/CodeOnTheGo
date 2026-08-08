package org.appdevforall.cotg.quickbuild.domain

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
	 * Always a reload, never an ordinary launch crash: the runtime's crash guard reports only
	 * while a reload is pending, and the rollback path only runs mid-apply.
	 *
	 * A crash in the user's own new code clears itself - the next save's payload supersedes
	 * the bad class. What has no self-healing is a payload broken for a reason no edit
	 * reaches: it is redeployed by every later reload, so it crashes the same way until the
	 * session is restarted. The copy therefore asks for the fix first and names the escape
	 * hatch - the Quick Build long-press menu's Restart session - for when that does not help.
	 */
	RELOAD_CRASHED,

	/**
	 * A deploy landed by hot swap in an app that has a live service, provider or custom
	 * `Application`, so an instance of one keeps calling the PREVIOUS copies of the helper
	 * classes this build recompiled until it restarts.
	 *
	 * Not a failure: the deploy worked and the recreated activity runs the new code.
	 * [DeployPolicy]'s restart closure covers a component's own code and its supertypes, and a
	 * hit there restarts the process; what it cannot see is a helper class the component
	 * merely calls.
	 */
	STALE_COMPONENT_HELPERS,

	/**
	 * aapt2 keeps rejecting the project's resources, so every save - including a pure-code one -
	 * fails on that same error until it is fixed.
	 *
	 * The relink links the whole `res/` tree from disk, not the changed set, so an unlinkable
	 * resource blocks the live reload path outright rather than only the save that introduced
	 * it. Almost always the user's own error, which their next good save clears; what has no
	 * self-healing is a reference the relink cannot resolve at all - a library resource absent
	 * from the proxy app build's resource snapshot - which no edit to the naming file fixes. The
	 * copy therefore asks for the fix first and names Restart session, whose fresh proxy app
	 * build resolves against the full resource set, for when that does not help.
	 *
	 * Deliberately not auto-escalated to a proxy app rebuild: the two cases are
	 * indistinguishable from aapt2's diagnostics, so escalating would spend ~200s of Gradle on
	 * an ordinary typo and drop the session to `Idle` when that build failed too.
	 */
	RELINK_STUCK,

	/**
	 * The proxy app cannot stay alive long enough to receive a payload, so every deploy fails
	 * "not connected" however many times the user relaunches.
	 *
	 * The shape is a baseline that crashes at startup - most often because provisioning ran
	 * while the app's own code was broken, baking the crash into the installed APK. Nothing the
	 * user edits reaches it: the fix compiles and dexes cleanly and then has nowhere to land,
	 * and the deploy failure's own "relaunch to reconnect" advice just restarts the crash.
	 *
	 * The only true remedy is a fresh proxy app build, so this notice is the one that asks for
	 * Restart session outright rather than suggesting a fix first - and the host raises it as a
	 * dialog with that action, because a flash the user can miss leaves them in a closed loop.
	 */
	PROXY_APP_WONT_STAY_UP,
}
