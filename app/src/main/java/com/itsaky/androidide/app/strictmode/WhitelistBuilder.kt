package com.itsaky.androidide.app.strictmode

import android.os.strictmode.Violation
import com.google.common.collect.ImmutableList

@DslMarker
private annotation class WhitelistBuilderDsl

@DslMarker
private annotation class WhitelistRuleBuilderDsl

@WhitelistRuleBuilderDsl
class RuleBuilder {

	private var violationType: Class<out Violation>? = null
	private var matcher: StackMatcher? = null
	private var reason: String? = null
	private var decision: WhitelistEngine.Decision = WhitelistEngine.Decision.Crash

	/**
	 * Sets the violation type to match.
	 *
	 * @param type The violation type to match.
	 */
	fun ofType(type: Class<out Violation>) {
		violationType = type
	}

	/**
	 * Sets the violation type to match.
	 *
	 * @param T The violation type to match.
	 */
	inline fun <reified T: Violation> ofType() = ofType(T::class.java)

	/**
	 * Matches the given adjacent frames.
	 *
	 * @param matchers The matchers to use.
	 */
	fun matchAdjacentFrames(vararg matchers: FrameMatcher) {
		matcher = StackMatcher.Adjacent(matchers.toList())
	}

	/**
	 * Matches the given frames in order.
	 *
	 * @param matchers The matchers to use.
	 */
	fun matchFramesInOrder(vararg matchers: FrameMatcher) {
		matcher = StackMatcher.InOrder(matchers.toList())
	}

	/**
	 * Matches the given adjacent groups of frames in order.
	 *
	 * @param groups The groups to match.
	 */
	fun matchAdjacentFramesInOrder(groups: List<List<FrameMatcher>>) {
		matcher = StackMatcher.AdjacentInOrder(groups)
	}

	/**
	 * Sets the decision to take when the violation is matched.
	 *
	 * @param decision The decision to take.
	 */
	fun decision(decision: WhitelistEngine.Decision) {
		this.decision = decision
	}

	/**
	 * Sets the reason for the whitelist.
	 *
	 * @param reason The reason for the whitelist.
	 */
	fun reason(reason: String) {
		this.reason = reason
	}

	/* ---- Build ---- */

	fun build(): WhitelistEngine.Rule {
		val type = requireNotNull(violationType) {
			"Violation type must be specified"
		}

		val matcher = requireNotNull(matcher) {
			"Stack matcher must be specified"
		}

		val reason = requireNotNull(reason) {
			"Reason must be specified"
		}

		matcher.checkPreconditions()

		require(reason.isNotBlank()) {
			"Reason must not be blank"
		}

		return WhitelistEngine.Rule(
			type = type,
			matcher = matcher,
			decision = decision,
			whitelistReason = reason
		)
	}
}


@WhitelistBuilderDsl
class WhitelistBuilder {

	private val rules = mutableListOf<WhitelistEngine.Rule>()

	/**
	 * Adds a whitelist rule.
	 *
	 * @param block The rule builder block.
	 */
	fun rule(block: RuleBuilder.() -> Unit) {
		rules += RuleBuilder().apply(block).build()
	}

	/**
	 * Build the whitelist.
	 */
	fun build(): List<WhitelistEngine.Rule> = ImmutableList.copyOf(rules)
}

/**
 * Builds a whitelist of strict mode violations.
 *
 * @param block The builder block.
 */
@WhitelistBuilderDsl
fun buildStrictModeWhitelist(block: WhitelistBuilder.() -> Unit): List<WhitelistEngine.Rule> =
	WhitelistBuilder().apply(block).build()
