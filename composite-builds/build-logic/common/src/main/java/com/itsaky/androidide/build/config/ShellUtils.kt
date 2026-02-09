package com.itsaky.androidide.build.config

import java.io.File

/**
 * Utilities for running shell commands.
 *
 * @author Akash Yadav
 */
object ShellUtils {
	fun which(cmd: String): String? = shC("which '$cmd'")

	fun shC(
		cmd: String,
		workDir: File? = null,
		redirectErrorStream: Boolean = true,
	): String? {
		val proc =
			ProcessBuilder("sh", "-c", cmd).run {
				if (workDir != null) {
					directory(workDir)
				}

				redirectErrorStream(redirectErrorStream)
				start()
			}

		val exitCode = proc.waitFor()
		if (exitCode != 0) {
			return null
		}

		return proc.inputStream
			.bufferedReader()
			.readText()
			.trim()
	}
}
