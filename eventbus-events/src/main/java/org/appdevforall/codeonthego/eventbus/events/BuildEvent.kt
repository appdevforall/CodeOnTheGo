package org.appdevforall.codeonthego.eventbus.events

import org.appdevforall.codeonthego.tooling.api.messages.BuildId
import org.appdevforall.codeonthego.tooling.api.messages.result.BuildInfo
import org.appdevforall.codeonthego.tooling.api.messages.result.BuildResult

/**
 * Events dispatched from the IDE's build service.
 *
 * @property buildId The build identifier.
 */
abstract class BuildEvent(
	val buildId: BuildId,
) : Event()

/**
 * Event dispatched when a Gradle build is started in the IDE.
 *
 * @property buildInfo Info about the build.
 */
class BuildStartedEvent(
	val buildInfo: BuildInfo,
): BuildEvent(buildInfo.buildId)

/**
 * Event dispatched when a Gradle build is completed in the IDE.
 *
 * @property result The result of the Gradle build.
 */
class BuildCompletedEvent(
	val result: BuildResult,
): BuildEvent(result.buildId)
