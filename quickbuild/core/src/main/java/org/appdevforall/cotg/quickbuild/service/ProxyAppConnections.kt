package org.appdevforall.cotg.quickbuild.service

import com.itsaky.androidide.quickbuild.IQuickBuildTarget
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory

/**
 * Registry of the currently bound proxy app and its reports, shared between the
 * Android-instantiated [QuickBuildHostService] and the session pipeline.
 *
 * The service cannot be constructor-injected because the system creates it, so both sides
 * meet here: the binder writes connections and reports in, the [DeployChannel] and session
 * manager read them out as flows. A class with a process-wide [INSTANCE], rather than an
 * object, so tests get isolated registries.
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
	}

	/**
	 * Publishes a proxy app that just bound, replacing any previous one.
	 *
	 * @param connection the bound target and the generation it reported at connect time;
	 *   that generation goes stale as soon as a hot swap lands without a rebind
	 */
	fun onConnected(connection: ConnectedTarget) {
		_target.value = connection
	}

	/** Publishes the loss of the bound proxy app, waking anyone awaiting a verdict. */
	fun onDisconnected() {
		_target.value = null
		_reports.tryEmit(TargetReport.Disconnected)
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
		private val log = LoggerFactory.getLogger(ProxyAppConnections::class.java)

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
