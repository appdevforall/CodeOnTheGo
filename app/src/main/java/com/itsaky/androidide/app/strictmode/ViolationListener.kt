package com.itsaky.androidide.app.strictmode

import android.os.StrictMode
import android.os.strictmode.Violation

/**
 * @author Akash Yadav
 */
class ViolationListener :
	StrictMode.OnThreadViolationListener,
	StrictMode.OnVmViolationListener {
	override fun onThreadViolation(v: Violation) {
		TODO("Not yet implemented")
	}

	override fun onVmViolation(v: Violation) {
		TODO("Not yet implemented")
	}
}
