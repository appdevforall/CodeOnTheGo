package com.itsaky.androidide.actions.build

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Who gets asked before a Run may clobber the project's applicationId (ADFA-4128).
 *
 * The predicate has a dangerous direction and a merely annoying one. Losing the confirm on an
 * APK-producing Run is the dangerous one: the install then silently replaces whatever third-party
 * app holds that applicationId. Raising it on a plugin Run is the annoying one - a plugin builds
 * a `.cgp` and installs nothing, so the dialog asks about a package that cannot be touched.
 */
class StandardRunClobberDecisionTest {
	@Test
	fun `a plugin project Run skips the confirm - a cgp installs no package`() {
		val decision = standardRunClobberDecision(isPluginProject = true, applicationId = "com.example.app")

		assertThat(decision).isEqualTo(StandardRunClobberDecision.SkipConfirm)
	}

	@Test
	fun `an app project Run asks first, naming the applicationId it would replace`() {
		val decision = standardRunClobberDecision(isPluginProject = false, applicationId = "com.example.app")

		assertThat(decision).isEqualTo(StandardRunClobberDecision.AskFirst("com.example.app"))
	}

	@Test
	fun `an applicationId that did not resolve still asks, with no id to name`() {
		// Blank and null both mean "we cannot name the occupant", and both must still ask: the
		// caller reads a null id as the unknown-occupant wording, not as permission to proceed.
		val blank = standardRunClobberDecision(isPluginProject = false, applicationId = "   ")
		val empty = standardRunClobberDecision(isPluginProject = false, applicationId = "")
		val absent = standardRunClobberDecision(isPluginProject = false, applicationId = null)

		assertThat(blank).isEqualTo(StandardRunClobberDecision.AskFirst(null))
		assertThat(empty).isEqualTo(StandardRunClobberDecision.AskFirst(null))
		assertThat(absent).isEqualTo(StandardRunClobberDecision.AskFirst(null))
	}
}
