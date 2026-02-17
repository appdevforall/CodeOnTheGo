package com.itsaky.androidide.tooling.api

import com.itsaky.androidide.tooling.api.messages.GradleBuildParams

/**
 * Client-level configuration for Gradle builds.
 *
 * @property buildParams The parameters for the Gradle build.
 * @property extraArgs Extra arguments to set to the Gradle build.
 * @author Akash Yadav
 */
class ClientGradleBuildConfig(
	val buildParams: GradleBuildParams = GradleBuildParams(),
	val extraArgs: List<String> = emptyList(),
)
