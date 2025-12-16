package com.itsaky.androidide.app.strictmode

import android.os.strictmode.DiskReadViolation
import com.itsaky.androidide.app.strictmode.FrameMatcher.Companion.classAndMethod
import com.itsaky.androidide.utils.FeatureFlags
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
	 * @property whitelistReason The reason for the whitelist.
	 */
	data class Rule(
		val type: Class<out StrictModeViolation>,
		val matcher: StackMatcher,
		val decision: Decision,
		val whitelistReason: String,
	)

	/**
	 * Whitelist engine decision.
	 */
	sealed interface Decision {

		/**
		 * Whitelist engine decision to allow the violation.
		 */
		data object Allow: Decision

		/**
		 * Whitelist engine decision to log the violation.
		 */
		data object Log: Decision

		/**
		 * Whitelist engine decision to crash the process upon violation.
		 */
		data object Crash: Decision
	}

	private val rules = buildStrictModeWhitelist {
		rule {
			ofType<DiskReadViolation>()
			decision(Decision.Allow)

			matchAdjacentFramesInOrder(
				listOf(
					listOf(
						classAndMethod("android.app.ContextImpl", "getSharedPreferences"),
						classAndMethod("com.google.firebase.internal.DataCollectionConfigStorage", "<init>")
					),
					listOf(
						classAndMethod("com.google.firebase.FirebaseApp\$UserUnlockReceiver", "onReceive")
					)
				)
			)

			reason("""
				Firebase tries to access shared preferences after device reboot, which may happen on
				the main thread, resulting in a DiskReadViolation.
			""".trimIndent())
		}
	}

	/**
	 * Evaluates the given [violation] and returns a decision based on the whitelist.
	 */
	fun evaluate(violation: ViolationDispatcher.Violation): Decision {
		val frames = violation.frames
		rules.firstOrNull { rule ->
			if (rule.matcher.matches(frames)) {
				return rule.decision
			}

			false
		}

		if (FeatureFlags.isReprieveEnabled) {
			return Decision.Log
		}

		return Decision.Crash
	}
}