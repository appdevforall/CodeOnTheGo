package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

data class AdbResult(
	val exitCode: Int,
	val stdout: String,
	val stderr: String,
)

fun interface Adb {
	fun run(args: List<String>): AdbResult
}

/** Runs [args] under `adb shell`. */
internal fun Adb.shell(vararg args: String): AdbResult = run(listOf("shell", *args))

/**
 * Turns a failed adb call into an error result.
 *
 * Shared by every adb-backed tool so they answer the same way: a failed call means
 * we do not know, which is never the same as a negative answer. Collapsing the two
 * would make an unreachable device look like a missing app, or an empty project.
 */
internal fun adbFailure(result: AdbResult): CallToolResult {
	val detail = result.stderr.trim().ifEmpty { result.stdout.trim() }
	return CallToolResult(
		content = listOf(TextContent("adb failed (exit ${result.exitCode}): $detail")),
		isError = true,
	)
}

class SystemAdb(
	private val executable: String = "adb",
) : Adb {
	override fun run(args: List<String>): AdbResult {
		val process = ProcessBuilder(listOf(executable) + args).start()

		// Drain stderr on its own thread: filling one pipe buffer while the other
		// goes unread deadlocks the child.
		val stderr = StringBuilder()
		val drain =
			Thread {
				process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) }
			}
		drain.start()

		val stdout = process.inputStream.bufferedReader().readText()
		drain.join()

		return AdbResult(exitCode = process.waitFor(), stdout = stdout, stderr = stderr.toString())
	}
}
