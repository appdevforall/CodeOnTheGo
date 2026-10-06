package com.itsaky.androidide.tooling.impl.util

import com.itsaky.androidide.tooling.api.messages.ClientGradleBuildConfig
import com.itsaky.androidide.tooling.api.messages.GradleBuildParams
import com.itsaky.androidide.tooling.impl.LoggingOutputStream
import com.itsaky.androidide.tooling.impl.Main
import com.itsaky.androidide.tooling.impl.progress.ForwardingProgressListener
import org.gradle.tooling.ConfigurableLauncher
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

private val logger = LoggerFactory.getLogger("GradleBuildExts")

/**
 * Configures this launcher's output, progress and arguments. [tasks] go into the argument list,
 * between the client's arguments and [buildParams]', as on a command line: a task option such as
 * `--tests` or `--rerun` binds to the task before it, and `forTasks` would leave it unbound.
 */
fun ConfigurableLauncher<*>.configureFrom(
	clientConfig: ClientGradleBuildConfig? = null,
	buildParams: GradleBuildParams? = null,
	tasks: List<String> = emptyList(),
) {
	logger.debug(
		"configuring build launcher: hasClientConfig={}, hasBuildParams: {}",
		clientConfig != null,
		buildParams != null,
	)

	val out = LoggingOutputStream()
	setStandardError(out)
	setStandardOutput(out)
	setStandardInput(ByteArrayInputStream("".toByteArray(StandardCharsets.UTF_8)))
	addProgressListener(ForwardingProgressListener(), Main.progressUpdateTypes())

	if (clientConfig != null) {
		val clientGradleArgs =
			clientConfig.buildParams.gradleArgs
				.filter(String::isNotBlank)
				.distinct()
		logger.debug("Client Gradle args: {}", clientGradleArgs)

		val clientJvmArgs = clientConfig.buildParams.jvmArgs.filter(String::isNotBlank)
		logger.debug("Client JVM args: {}", clientJvmArgs)

		addArguments(clientGradleArgs)
		addJvmArguments(clientJvmArgs)
	}

	addArguments(tasks.filter(String::isNotBlank))

	if (buildParams != null) {
		val gradleArgs = buildParams.gradleArgs.filter(String::isNotBlank)
		logger.debug("Build Gradle args: {}", gradleArgs)

		val jvmArgs = buildParams.jvmArgs.filter(String::isNotBlank)
		logger.debug("Build JVM args: {}", jvmArgs)

		addArguments(gradleArgs)
		addJvmArguments(jvmArgs)
	}
}
