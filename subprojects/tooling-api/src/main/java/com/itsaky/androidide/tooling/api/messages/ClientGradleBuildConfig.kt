package com.itsaky.androidide.tooling.api.messages

import java.io.Serializable

/**
 * Client-level configuration for Gradle builds.
 *
 * @property buildParams The parameters for the Gradle build.
 * @author Akash Yadav
 */
data class ClientGradleBuildConfig(
	val buildParams: GradleBuildParams = GradleBuildParams(),
) : Serializable
