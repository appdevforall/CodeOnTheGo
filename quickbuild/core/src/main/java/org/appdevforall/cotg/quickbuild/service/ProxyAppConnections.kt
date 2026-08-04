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

	/** Opens the registry to one proxy app, the only caller accepted until [endSession]. */
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

	/** Publishes a proxy app that just bound, replacing any previous one. */
	fun onConnected(connection: ConnectedTarget) {
		_target.value = connection
	}

	/** Publishes the loss of the bound proxy app, waking anyone awaiting a verdict. */
	fun onDisconnected() {
		_target.value = null
		_reports.tryEmit(TargetReport.Disconnected)
	}

	/** Publishes one report from the proxy app to [reports]. */
	fun report(report: TargetReport) {
		_reports.tryEmit(report)
	}

	companion object {
		private val log = LoggerFactory.getLogger(ProxyAppConnections::class.java)

		/** Process-wide registry the Android service and the Koin graph both use. */
		val INSTANCE = ProxyAppConnections()
	}
}

/** A bound proxy app and the generation it reported running at connect time. */
data class ConnectedTarget(
	val target: IQuickBuildTarget,
	val packageName: String,
	val runningGeneration: Long,
)

/** Feedback from the proxy app after a deploy (or its death). */
sealed interface TargetReport {
	data class Reloaded(
		val generation: Long,
		val reloadMillis: Long,
	) : TargetReport

	data class Crashed(
		val generation: Long,
		val stackSummary: String,
	) : TargetReport

	data object Disconnected : TargetReport
}
