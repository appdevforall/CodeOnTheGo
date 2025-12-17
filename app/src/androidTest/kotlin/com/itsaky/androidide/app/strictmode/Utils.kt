package com.itsaky.androidide.app.strictmode

import android.os.strictmode.Violation
import com.google.common.truth.Truth.assertThat

inline fun <reified T : Violation> createViolation(
	frames: List<StackFrame>,
	type: ViolationDispatcher.ViolationType = ViolationDispatcher.ViolationType.THREAD
): ViolationDispatcher.Violation {
	val violation = T::class.java.getConstructor().newInstance()
	violation.stackTrace = frames.toTypedArray()

	return ViolationDispatcher.Violation(
		violation = violation,
		type = type
	)
}

fun stackTraceElement(
	className: String,
	methodName: String,
	fileName: String = "${className}.java",
	@Suppress("SameParameterValue") lineNumber: Int = 1,
) = StackTraceElement(className, methodName, fileName, lineNumber)

/**
 * Asserts that the violation of provided type is allowed by the [WhitelistEngine].
 *
 * @param frames The frames of the violation.
 */
inline fun <reified T: Violation> assertAllowed(vararg frames: StackFrame) {
	val violation = createViolation<T>(frames.toList())
	val decision = WhitelistEngine.evaluate(violation)
	assertThat(decision).isInstanceOf(WhitelistEngine.Decision.Allow::class.java)
}
