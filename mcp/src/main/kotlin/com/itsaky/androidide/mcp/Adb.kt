package com.itsaky.androidide.mcp

data class AdbResult(
	val exitCode: Int,
	val stdout: String,
	val stderr: String,
)

fun interface Adb {
	fun run(args: List<String>): AdbResult
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
