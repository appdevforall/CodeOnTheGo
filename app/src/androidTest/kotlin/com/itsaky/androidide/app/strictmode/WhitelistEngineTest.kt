package com.itsaky.androidide.app.strictmode

import android.os.strictmode.CustomViolation
import android.os.strictmode.DiskReadViolation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhitelistEngineTest {

	@Before
	fun install() {
		runBlocking {
			StrictModeManager.install(
				StrictModeConfig(
					enabled = true,
					isReprieveEnabled = false, // disable reprieve so that 'Crash' is the default decision
				)
			)
		}
	}

	@Test
	fun evaluateReturnsAllowForWhitelistedViolation() {

		// add a custom whitelist rule
		mockkObject(WhitelistEngine)
		every { WhitelistEngine.rules } returns buildStrictModeWhitelist {
			rule {
				ofType<CustomViolation>()
				allow("")
				matchFramesInOrder(
					FrameMatcher.classAndMethod("com.example.SomeClass", "someMethod")
				)
			}
		}

		// create a frame that matches the whitelist rule
		val violatingFrames = listOf(
			stackTraceElement(
				"com.example.SomeOtherClass",
				"someOtherMethod",
				"SomeOtherClass.java",
				1
			),
			stackTraceElement("com.example.SomeClass", "someMethod", "SomeClass.java", 1),
			stackTraceElement("com.example.AnotherClass", "anotherMethod", "AnotherClass.java", 1)
		)

		// verify that the violation is allowed
		val violation = createViolation<DiskReadViolation>(violatingFrames)
		val decision = WhitelistEngine.evaluate(violation)
		assertThat(decision).isInstanceOf(WhitelistEngine.Decision.Allow::class.java)
	}

	@Test
	fun evaluateReturnsCrashForNonWhitelistedViolation() {

		// disable the whitelist
		mockkObject(WhitelistEngine)
		every { WhitelistEngine.rules } returns emptyList()

		val frames = listOf(
			stackTraceElement(
				className = "com.example.SomeClass",
				methodName = "someMethod",
				fileName = "SomeClass.java",
				lineNumber = 1
			)
		)

		val violation = createViolation<DiskReadViolation>(frames)
		val decision = WhitelistEngine.evaluate(violation)
		assertEquals(WhitelistEngine.Decision.Crash, decision)
	}
}
