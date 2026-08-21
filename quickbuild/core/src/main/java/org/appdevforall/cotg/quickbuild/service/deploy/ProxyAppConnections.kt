package org.appdevforall.cotg.quickbuild.service.deploy

import android.os.IBinder
import com.itsaky.androidide.quickbuild.IQuickBuildTarget
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory

/**
 * Registry of the currently bound proxy app and its reports.
 *
 * [QuickBuildHostService] cannot be constructor-injected because the system creates it, so both
 * sides meet here: the binder writes connections and reports in, the [DeployChannel] and session
 * manager read them out as flows. A class with a process-wide [INSTANCE], rather than an object,
 * so tests get isolated registries.
 */
class ProxyAppConnections {
	/**
	 * The only uid inbound binder calls are accepted from, read from the installed proxy
	 * app's PackageManager entry at session start. Null means no live session, so every
	 * inbound call is rejected.
	 */
	@Volatile var expectedUid: Int? = null
		private set

	/** Package name that goes with [expectedUid]; null when no session is live. */
	@Volatile var expectedPackage: String? = null
		private set

	private val _target = MutableStateFlow<ConnectedTarget?>(null)

	/** The currently bound proxy app, or null when none is connected. */
	val target: StateFlow<ConnectedTarget?> = _target

	// Buffered so binder threads never suspend; a report burst beyond the buffer is
	// dropped-oldest, which only ever loses superseded generations' reports.
	private val _reports =
		MutableSharedFlow<TargetReport>(
			extraBufferCapacity = 64,
			onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
		)

	/** Reload/crash/disconnect reports from the proxy app, in arrival order. */
	val reports: SharedFlow<TargetReport> = _reports

	/**
	 * Keeps the connected proxy app out of the cached-app freezer; null until
	 * [installPriorityHold] runs, and on the JVM in tests that do not care.
	 */
	@Volatile private var priorityHold: ProxyAppPriorityHold? = null

	/**
	 * Supplies the hold this registry drives, replacing any previous one.
	 *
	 * Separate from construction because the only object with a Context is the
	 * Android-instantiated [QuickBuildHostService], which the system creates long after this
	 * process-wide registry exists.
	 *
	 * @param hold the hold to take on connect and drop on disconnect or session end
	 */
	fun installPriorityHold(hold: ProxyAppPriorityHold) {
		priorityHold = hold
	}

	/** Drops the hold and forgets it, for when the object that supplied it is going away. */
	fun uninstallPriorityHold() {
		priorityHold?.release()
		priorityHold = null
	}

	/**
	 * Opens the registry to one proxy app, the only caller accepted until [endSession].
	 *
	 * @param packageName the installed proxy app's package, for logging and reporting only
	 * @param uid the uid PackageManager reports for that package; this is the whole trust
	 *   boundary of the exported host service, so it must come from PackageManager and
	 *   never from anything the caller sent
	 */
	fun beginSession(
		packageName: String,
		uid: Int,
	) {
		log.info("Quick-build session accepts proxy app {} (uid {})", packageName, uid)
		expectedPackage = packageName
		expectedUid = uid
	}

	/** Closes the registry: no proxy app is accepted again until the next [beginSession]. */
	fun endSession() {
		expectedPackage = null
		expectedUid = null
		_target.value = null
		// Nothing can deploy to the app now, so stop exempting it from the freezer: a hold
		// kept past session end would cost the user battery on an app they are just running.
		priorityHold?.release()
	}

	/**
	 * Publishes a proxy app that just bound, replacing any previous one.
	 *
	 * @param connection the bound target and the generation it reported at connect time;
	 *   that generation goes stale as soon as a hot swap lands without a rebind
	 */
	fun onConnected(connection: ConnectedTarget) {
		_target.value = connection
		// Hold the PackageManager-sourced package, never connection.packageName: that one is
		// the caller's own report, and this call starts and keeps alive a process by name.
		// Null means no live session, in which case there is nothing to protect.
		expectedPackage?.let { priorityHold?.hold(it) }
	}

	/**
	 * Publishes the loss of the bound proxy app, waking anyone awaiting a verdict.
	 *
	 * @param died the binder whose death prompted this, or null to drop unconditionally
	 *   (session end, or the app's own goodbye). A death notification from a superseded proxy
	 *   app process arrives after its replacement has already registered, so a non-null value
	 *   that is not the registered binder is ignored: clearing there would deploy the next
	 *   save into NotConnected against a healthy bound app, and drop the freezer hold with it.
	 */
	fun onDisconnected(died: IBinder? = null) {
		val current = _target.value
		if (died != null && current != null && current.target.asBinder() !== died) {
			log.info("Ignoring death of a superseded proxy app binder; a live one is registered")
			return
		}
		_target.value = null
		_reports.tryEmit(TargetReport.Disconnected)
		// No process left to protect. A relaunch reconnects and [onConnected] re-takes it.
		priorityHold?.release()
	}

	/**
	 * Publishes one report from the proxy app to [reports].
	 *
	 * @param report the inbound report; dropped silently if the buffer is full, which only
	 *   ever discards a superseded generation's report
	 */
	fun report(report: TargetReport) {
		_reports.tryEmit(report)
	}

	companion object {
		private val log = LoggerFactory.getLogger("QB-ProxyConnections")

		/** Process-wide registry the Android service and the Koin graph both use. */
		val INSTANCE = ProxyAppConnections()
	}
}

/**
 * A bound proxy app and the generation it reported running at connect time.
 *
 * @property target the AIDL callback every deploy travels over
 * @property packageName the proxy app's own report of its package, for logging only - the
 *   uid gate, not this, is what authorizes the caller
 * @property runningGeneration fresh only at connect time; it goes stale as soon as a hot
 *   swap lands without a rebind, so prefer the session's own deploy tally when there is one
 */
data class ConnectedTarget(
	val target: IQuickBuildTarget,
	val packageName: String,
	val runningGeneration: Long,
)

/** Feedback from the proxy app after a deploy (or its death). */
sealed interface TargetReport {
	/**
	 * A payload went live.
	 *
	 * @property generation the payload's generation, which the waiter matches against its
	 *   own so a superseded build's report is never mistaken for the current one
	 * @property reloadMillis the app's own measure of the reload, ending at the recreated
	 *   activity's onResume
	 */
	data class Reloaded(
		val generation: Long,
		val reloadMillis: Long,
	) : TargetReport

	/**
	 * A payload reached the app but threw in render or lifecycle.
	 *
	 * @property generation the payload's generation, matched the same way as [Reloaded]
	 * @property stackSummary one-line summary of the throwable, surfaced to the user
	 */
	data class Crashed(
		val generation: Long,
		val stackSummary: String,
	) : TargetReport

	/**
	 * The bound app went away. Carries no generation because it answers every waiter, not
	 * just the one whose payload was in flight.
	 */
	data object Disconnected : TargetReport
}
