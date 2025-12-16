package com.itsaky.androidide.app.strictmode

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
		val violation = Violation(violation, type)
		val decision = WhitelistEngine.evaluate(violation)
		when (decision) {
			WhitelistEngine.Decision.Allow -> return
			WhitelistEngine.Decision.Log -> ViolationHandler.log(violation)
			WhitelistEngine.Decision.Crash -> ViolationHandler.crash(violation)
		}
	}
}