package com.itsaky.androidide.app.strictmode

import androidx.annotation.Keep
import android.os.strictmode.Violation as StrictModeViolation

typealias StackFrame = StackTraceElement

/**
 * Violation dispatcher is responsible for handling violations in threads and the VM.
 *
 * @author Akash Yadav
 */
object ViolationDispatcher {

	/**
	 * Violation types.
	 */
	@Keep
	enum class ViolationType {

		/**
		 * Violation in a thread.
		 */
		THREAD,

		/**
		 * Violation in the VM.
		 */
		VM
	}

	/**
	 * A strict mode violation.
	 */
	data class Violation(
		val violation: StrictModeViolation,
		val type: ViolationType,
	) {
		/**
		 * The stack frames of the violation.
		 */
		val frames: List<StackFrame>
			get() = violation.stackTrace.toList()
	}

	/**
	 * Called when a violation is detected in a thread.
	 *
	 * @param violation The violation that was detected.
	 */
	fun onThreadViolation(violation: StrictModeViolation) = dispatch(violation, ViolationType.THREAD)

	/**
	 * Called when a violation is detected in the VM.
	 *
	 * @param violation The violation that was detected.
	 */
	fun onVmViolation(violation: StrictModeViolation) = dispatch(violation, ViolationType.VM)

	private fun dispatch(violation: StrictModeViolation, type: ViolationType) {
		val wrappedViolation = Violation(violation, type)
		when (val decision = WhitelistEngine.evaluate(wrappedViolation)) {
			is WhitelistEngine.Decision.Allow -> ViolationHandler.allow(wrappedViolation, decision.reason)
			WhitelistEngine.Decision.Log -> ViolationHandler.log(wrappedViolation)
			WhitelistEngine.Decision.Crash -> ViolationHandler.crash(wrappedViolation)
		}
	}
}