package com.itsaky.androidide.app.strictmode

import android.os.strictmode.Violation

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