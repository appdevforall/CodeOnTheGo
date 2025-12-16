package com.itsaky.androidide.app.strictmode

import android.os.strictmode.DiskReadViolation
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhitelistEngineTest {

	@Test
	fun evaluateReturnsAllowForWhitelistedViolation() {
		val frames = listOf(
			stackTraceElement(
				"android.app.ContextImpl", "getSharedPreferences", "ContextImpl.java", 1
			), stackTraceElement(
				"com.google.firebase.internal.DataCollectionConfigStorage",
				"<init>",
				"DataCollectionConfigStorage.java",
				1
			), stackTraceElement(
				"com.google.firebase.FirebaseApp\$UserUnlockReceiver",
				"onReceive",
				"FirebaseApp.java",
				1
			)
		)

		val violation = createViolation<DiskReadViolation>(frames)
		val decision = WhitelistEngine.evaluate(violation)
		assertEquals(WhitelistEngine.Decision.Allow, decision)
	}

	@Test
	fun evaluateReturnsCrashForNonWhitelistedViolation() {
		val frames = listOf(
			stackTraceElement("com.example.SomeClass", "someMethod", "SomeClass.java", 1)
		)
		val violation = createViolation<DiskReadViolation>(frames)
		val decision = WhitelistEngine.evaluate(violation)
		assertEquals(WhitelistEngine.Decision.Crash, decision)
	}

	private fun stackTraceElement(
		className: String,
		methodName: String,
		fileName: String,
		@Suppress("SameParameterValue") lineNumber: Int
	): StackTraceElement {
		return StackTraceElement(className, methodName, fileName, lineNumber)
	}
}
