package com.itsaky.androidide.testing

object InstrumentationStateProbe {

	@Volatile
	var projectInitState: ProbeProjectInitState = ProbeProjectInitState.UNKNOWN

	@Volatile
	var buildState: ProbeBuildState = ProbeBuildState.IDLE

	fun reset() {
		projectInitState = ProbeProjectInitState.UNKNOWN
		buildState = ProbeBuildState.IDLE
	}
}

enum class ProbeProjectInitState {
	UNKNOWN,
	INITIALIZING,
	INITIALIZED,
	FAILED,
}

enum class ProbeBuildState {
	IDLE,
	IN_PROGRESS,
	SUCCESS,
	FAILED,
}
