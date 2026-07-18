package org.appdevforall.cotg.quickbuild.service

import com.itsaky.androidide.quickbuild.IQuickBuildTarget
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory

/**
 * Shared state between the Android-instantiated [QuickBuildHostService] and the
 * session pipeline. The service can't be constructor-injected (the system creates
 * it), so both sides meet on this registry: the binder writes connections/reports in,
 * the [DeployChannel] and session manager read them out as flows.
 *
 * A class (with a process-wide [INSTANCE]) rather than an object so tests get fresh,
 * isolated registries.
 */
class TestAppConnections {
	/**
	 * The uid the deploy channel accepts calls from - recorded at session start from
	 * the installed test app's PackageManager entry. Null means no live session: every
	 * inbound call is rejected.
	 */
	@Volatile var expectedUid: Int? = null
		private set

	@Volatile var expectedPackage: String? = null
		private set

	private val _target = MutableStateFlow<ConnectedTarget?>(null)

	/** The currently bound test app, or null when none is connected. */
	val target: StateFlow<ConnectedTarget?> = _target

	// Buffered so binder threads never suspend; a report burst beyond the buffer is
	// dropped-oldest, which only ever loses superseded generations' reports.
	private val _reports =
		MutableSharedFlow<TargetReport>(
			extraBufferCapacity = 64,
			onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
		)

	/** Reload/crash/disconnect reports from the test app, in arrival order. */
	val reports: SharedFlow<TargetReport> = _reports

	fun beginSession(
		packageName: String,
		uid: Int,
	) {
		log.info("Quick-build session accepts test app {} (uid {})", packageName, uid)
		expectedPackage = packageName
		expectedUid = uid
	}

	fun endSession() {
		expectedPackage = null
		expectedUid = null
		_target.value = null
	}

	fun onConnected(connection: ConnectedTarget) {
		_target.value = connection
	}

	fun onDisconnected() {
		_target.value = null
		_reports.tryEmit(TargetReport.Disconnected)
	}

	fun report(report: TargetReport) {
		_reports.tryEmit(report)
	}

	companion object {
		private val log = LoggerFactory.getLogger(TestAppConnections::class.java)

		/** Process-wide registry the Android service and the Koin graph both use. */
		val INSTANCE = TestAppConnections()
	}
}

/** A bound test app and the generation it reported running at connect time. */
data class ConnectedTarget(
	val target: IQuickBuildTarget,
	val packageName: String,
	val runningGeneration: Long,
)

/** Feedback from the test app after a deploy (or its death). */
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
