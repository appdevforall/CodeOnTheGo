package com.itsaky.androidide.utils

/**
 * Every feature still behind the experiments flag. Gate with [Experiments.isOn]; see
 * EXPERIMENTS.md. Graduating a feature means deleting its entry: the compiler then flags each
 * remaining gate, where the "on" branch stays and the "off" branch goes.
 *
 * [jiraKey] is the open ticket that decides the experiment's fate. A nightly job reports any
 * entry whose ticket is Done.
 */
enum class Experiment(
	val label: String,
	val jiraKey: String,
) {
	CRASH_ON_ICON_LONG_PRESS("Long-press onboarding icon crashes the app", "ADFA-6513"),
	RAISED_STORAGE_MINIMUM("6 GB minimum free storage", "ADFA-6514"),
	QUICK_BUILD("Quick Build", "ADFA-6515"),
	ERROR_BAR_TOUCH_DISMISS("Error bars close on tap or swipe", "ADFA-6516"),
	PROFILER("CPU and RAM profiler", "ADFA-6517"),
	GRADLE_AUTO_TUNE("Gradle build auto-tune", "ADFA-6518"),
}

object Experiments {
	/**
	 * The overall switch (`CodeOnTheGo.exp` in Downloads). Only for permanent consumers of the
	 * switch itself - the title marker, the plugin API's IdeFeatureFlagService, and zip
	 * templates that mark themselves experimental. Gate a feature with [isOn] instead.
	 */
	val enabled: Boolean
		get() = FeatureFlags.isExperimentsEnabled

	// Every entry shares the one switch today; the parameter is what makes a deleted entry a
	// compile error at its gates.
	@Suppress("UNUSED_PARAMETER")
	fun isOn(experiment: Experiment): Boolean = enabled
}
