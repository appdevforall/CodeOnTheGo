package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.GradleTaskResult
import java.util.concurrent.CompletableFuture

/** One run of Gradle tasks on the tooling server, as [IdeBuildServiceImpl.startTasks] starts it. */
internal interface GradleTaskRun {
	val result: CompletableFuture<GradleTaskResult>

	/** What Gradle printed for this run, whole once [result] completes. Empty for a refused run. */
	fun output(): String

	/** Cancels this run's build while it still holds the slot; completes false otherwise. */
	fun cancel(): CompletableFuture<Boolean>
}

internal fun interface GradleTaskRunner {
	fun start(
		tasks: List<String>,
		arguments: List<String>,
	): GradleTaskRun
}

/** The last [maxChars] characters of a build's output, in whole lines. Thread-safe. */
internal class BuildOutputCapture(
	private val maxChars: Int,
) {
	private val lines = ArrayDeque<String>()
	private var chars = 0

	@Synchronized
	fun append(line: String) {
		lines.addLast(line)
		chars += line.length + 1
		while (chars > maxChars && lines.size > 1) chars -= lines.removeFirst().length + 1
	}

	@Synchronized
	fun text(): String = lines.joinToString("\n")
}
