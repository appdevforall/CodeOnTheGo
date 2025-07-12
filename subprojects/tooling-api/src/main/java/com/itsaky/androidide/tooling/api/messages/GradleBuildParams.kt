package com.itsaky.androidide.tooling.api.messages

import java.io.Serializable

/**
 * Parameters for a Gradle build.
 *
 * @property gradleArgs Extra arguments to set to the Gradle build.
 * @author Akash Yadav
 */
abstract class GradleBuildParams(
    val gradleArgs: List<String> = emptyList(),
    val jvmArgs: List<String> = emptyList(),
): Serializable