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
				),
			)
		}
	}

	@Test
	fun evaluateReturnsAllowForWhitelistedViolation() {
		// add a custom whitelist rule
		mockkObject(WhitelistEngine)
		every { WhitelistEngine.rules } returns
			buildStrictModeWhitelist {
				rule {
					ofType<CustomViolation>()
					allow("")
					matchFramesInOrder(
						FrameMatcher.classAndMethod("com.example.SomeClass", "someMethod"),
					)
				}
			}

		// create a frame that matches the whitelist rule
		val violatingFrames =
			listOf(
				stackTraceElement(
					"com.example.SomeOtherClass",
					"someOtherMethod",
					"SomeOtherClass.java",
					1,
				),
				stackTraceElement("com.example.SomeClass", "someMethod", "SomeClass.java", 1),
				stackTraceElement("com.example.AnotherClass", "anotherMethod", "AnotherClass.java", 1),
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

		val frames =
			listOf(
				stackTraceElement(
					className = "com.example.SomeClass",
					methodName = "someMethod",
					fileName = "SomeClass.java",
					lineNumber = 1,
				),
			)

		val violation = createViolation<DiskReadViolation>(frames)
		val decision = WhitelistEngine.evaluate(violation)
		assertEquals(WhitelistEngine.Decision.Crash, decision)
	}

	@Test
	fun evaluateReturnsAllowForOplusUIFirstDiskReadViolation() {
		val violatingFrames =
			listOf(
				// Minimal "realistic" prelude (as in Sentry)
				stackTraceElement("android.os.StrictMode\$AndroidBlockGuardPolicy", "onReadFromDisk", "StrictMode.java", 1772,),
				stackTraceElement("libcore.io.BlockGuardOs", "access", "BlockGuardOs.java", 74,),
				stackTraceElement("java.io.UnixFileSystem", "checkAccess", "UnixFileSystem.java", 337,),

				// Whitelisted sequence (adjacent, in-order)
				stackTraceElement("java.io.File", "exists", "File.java", 829),
				stackTraceElement("com.oplus.uifirst.Utils", "writeProcNode", "Utils.java", 139),
				stackTraceElement("com.oplus.uifirst.OplusUIFirstManager", "writeProcNode", "OplusUIFirstManager.java", 382,),
				stackTraceElement("com.oplus.uifirst.OplusUIFirstManager", "setBinderThreadUxFlag", "OplusUIFirstManager.java", 877,),

				// Minimal tail (system server / wm — optional but matches Sentry shape)
				stackTraceElement("com.android.server.wm.ActivityRecordExtImpl", "hookSetBinderUxFlag", "ActivityRecordExtImpl.java", 3008,),
			)

		val violation = createViolation<DiskReadViolation>(violatingFrames)
		val decision = WhitelistEngine.evaluate(violation)

		assertThat(decision).isInstanceOf(WhitelistEngine.Decision.Allow::class.java)

		val allow = decision as WhitelistEngine.Decision.Allow
		assertThat(allow.reason).contains("Oplus")
	}

	@Test
	fun evaluateReturnsAllowForUtilCodeSPUtilsInitialization() {
		val violatingFrames = listOf(
			stackTraceElement("android.os.StrictMode\$AndroidBlockGuardPolicy", "onReadFromDisk", "StrictMode.java", 1728),
			stackTraceElement("java.io.File", "exists", "File.java", 829),
			stackTraceElement("android.app.ContextImpl", "getSharedPreferences", "ContextImpl.java", 605),

			stackTraceElement("com.blankj.utilcode.util.SPUtils", "<init>", "SPUtils.java", 84),
			stackTraceElement("com.blankj.utilcode.util.SPUtils", "getInstance", "SPUtils.java", 71),

			stackTraceElement("com.itsaky.androidide.activities.SplashActivity", "onCreate", "SplashActivity.kt", 34)
		)

		val violation = createViolation<DiskReadViolation>(violatingFrames)
		val decision = WhitelistEngine.evaluate(violation)

		assertThat(decision).isInstanceOf(WhitelistEngine.Decision.Allow::class.java)
		val allow = decision as WhitelistEngine.Decision.Allow
		assertThat(allow.reason).contains("SPUtils initialization")
	}

	@Test
	fun evaluateReturnsAllowForUtilCodeLanguageUtilsDiskRead() {
		val violatingFrames = listOf(
			stackTraceElement("android.os.StrictMode\$AndroidBlockGuardPolicy", "onReadFromDisk", "StrictMode.java", 1728),
			stackTraceElement("android.app.SharedPreferencesImpl", "awaitLoadedLocked", "SharedPreferencesImpl.java", 283),

			stackTraceElement("com.blankj.utilcode.util.SPUtils", "getString", "SPUtils.java", 131),
			stackTraceElement("com.blankj.utilcode.util.LanguageUtils", "applyLanguage", "LanguageUtils.java", 231),

			stackTraceElement("com.blankj.utilcode.util.UtilsActivityLifecycleImpl", "onActivityCreated", "UtilsActivityLifecycleImpl.java", 200),
			stackTraceElement("com.itsaky.androidide.activities.OnboardingActivity", "onCreate", "OnboardingActivity.kt", 111)
		)

		val violation = createViolation<DiskReadViolation>(violatingFrames)
		val decision = WhitelistEngine.evaluate(violation)

		assertThat(decision).isInstanceOf(WhitelistEngine.Decision.Allow::class.java)
		val allow = decision as WhitelistEngine.Decision.Allow
		assertThat(allow.reason).contains("LanguageUtils reading persisted locale")
	}
}
