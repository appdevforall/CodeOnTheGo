package com.itsaky.androidide.app.strictmode

import android.os.strictmode.DiskReadViolation
import androidx.annotation.VisibleForTesting
import com.itsaky.androidide.app.strictmode.FrameMatcher.Companion.classAndMethod
import android.os.strictmode.Violation as StrictModeViolation

/**
 * @author Akash Yadav
 */
object WhitelistEngine {
	/**
	 * A whitelist rule.
	 *
	 * @property type The type of violation to match.
	 * @property matcher The matcher to use to match the violation.
	 * @property decision The decision to take when the violation is matched.
	 */
	data class Rule(
		val type: Class<out StrictModeViolation>,
		val matcher: StackMatcher,
		val decision: Decision,
	)

	/**
	 * Whitelist engine decision.
	 */
	sealed interface Decision {
		/**
		 * Whitelist engine decision to allow the violation.
		 *
		 * @property reason The reason for allowing the violation.
		 */
		data class Allow(
			val reason: String,
		) : Decision

		/**
		 * Whitelist engine decision to log the violation.
		 */
		data object Log : Decision

		/**
		 * Whitelist engine decision to crash the process upon violation.
		 */
		data object Crash : Decision
	}

	@VisibleForTesting
	internal val rules =
		buildStrictModeWhitelist {

			// When adding a new rule, add rules to the bottom of the whitelist and ensure it is covered
			// by test cases in WhitelistRulesTest.kt

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					Firebase's UserUnlockReceiver tries to access shared preferences after device reboot,
					which may happen on the main thread, resulting in a DiskReadViolation. Since we can't
					control when UserUnlockReceiver is called, we allow this violation.
					""".trimIndent(),
				)

				matchAdjacentFramesInOrder(
					listOf(
						listOf(
							classAndMethod("android.app.ContextImpl", "getSharedPreferences"),
							classAndMethod(
								"com.google.firebase.internal.DataCollectionConfigStorage",
								"<init>",
							),
						),
						listOf(
							classAndMethod(
								"com.google.firebase.FirebaseApp\$UserUnlockReceiver",
								"onReceive",
							),
						),
					),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					Firebase's DataCollectionConfigStorage reads the 'auto data collection' flag from
					SharedPreferences during initialization triggered by UserUnlockReceiver. This causes
					a DiskReadViolation on the main thread. Since this is an internal behavior of the
					Firebase SDK that we cannot control, we allow this violation.
					""".trimIndent(),
				)

				matchAdjacentFramesInOrder(
					listOf(
						listOf(
							classAndMethod(
								"com.google.firebase.internal.DataCollectionConfigStorage",
								"readAutoDataCollectionEnabled"
							),
							classAndMethod(
								"com.google.firebase.internal.DataCollectionConfigStorage",
								"<init>"
							),
						),
						listOf(
							classAndMethod(
								"com.google.firebase.FirebaseApp\$UserUnlockReceiver",
								"onReceive"
							),
						),
					),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					MIUI's TextView implementation has a MultiLangHelper which is invoked during draw.
					For some reason, it tries to check whether a file exists, resulting in a
					DiskReadViolation. Since we can't control when MultiLangHelper is called, we allow
					this violation.
					""".trimIndent(),
				)

				matchAdjacentFrames(
					classAndMethod("miui.util.font.MultiLangHelper", "initMultiLangInfo"),
					classAndMethod("miui.util.font.MultiLangHelper", "<clinit>"),
					classAndMethod("android.graphics.LayoutEngineStubImpl", "drawTextBegin"),
					classAndMethod("android.widget.TextView", "onDraw"),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					On MediaTek devices, the 'ScnModule' is primarily used for scenario detection and
					power management, like detecting whether a running app is a game. When doing this
					check, it tries to read a file, resulting in a DiskReadViolation. Since we can't
					control when ScnModule is called, we allow this violation.
					""".trimIndent(),
				)

				matchAdjacentFrames(
					classAndMethod("java.io.File", "length"),
					classAndMethod("com.mediatek.scnmodule.ScnModule", "isGameAppFileSize"),
					classAndMethod("com.mediatek.scnmodule.ScnModule", "isGameApp"),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					Oplus/ColorOS UIFirst writes to proc nodes during activity transitions (startActivity),
					which triggers a DiskReadViolation (File.exists) on some devices/ROM versions.
					This happens in framework code and is outside app control, so we allow it.
					""".trimIndent(),
				)

				matchAdjacentFrames(
					classAndMethod("java.io.File", "exists"),
					classAndMethod("com.oplus.uifirst.Utils", "writeProcNode"),
					classAndMethod("com.oplus.uifirst.OplusUIFirstManager", "writeProcNode"),
					classAndMethod("com.oplus.uifirst.OplusUIFirstManager", "setBinderThreadUxFlag"),
				)
			}
		}

	/**
	 * Evaluates the given [violation] and returns a decision based on the whitelist.
	 */
	fun evaluate(violation: ViolationDispatcher.Violation): Decision {
		val frames = violation.frames
		rules.firstOrNull { rule ->
			if (rule.type.isInstance(violation.violation) && rule.matcher.matches(frames)) {
				return rule.decision
			}

			false
		}

		if (StrictModeManager.config.isReprieveEnabled) {
			return Decision.Log
		}

		return Decision.Crash
	}
}
