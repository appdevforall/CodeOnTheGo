package com.itsaky.androidide.tooling.api.messages

import java.io.Serializable

/**
 * A build identifier.
 *
 * @author Akash Yadav
 */
data class BuildId(
	val buildSessionId: String,
	val buildId: Long,
	val runType: BuildRunType,
) : Serializable {
	companion object {
		val Unknown = BuildId("unknown", -1, BuildRunType.TaskRun)
	}
}

/**
 * The type of Gradle build run.
 */
enum class BuildRunType(
	val typeName: String,
) {

	/**
	 * Gradle build for project synchronization.
	 */
	ProjectSync("sync"),

	/**
	 * Gradle build for running one or more tasks.
	 */
	TaskRun("taskRun"),
}
