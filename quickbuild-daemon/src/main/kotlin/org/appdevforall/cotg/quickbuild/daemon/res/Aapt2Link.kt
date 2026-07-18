package org.appdevforall.cotg.quickbuild.daemon.res

import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import java.io.File
import java.util.zip.ZipFile

/**
 * Shells the device-provisioned aapt2 to rebuild the resource table after a res-only
 * (or mixed) save: compile every res dir to .flat files, link them against android.jar
 * with the test-app manifest, then extract `resources.arsc` from the link output - the
 * payload the runtime feeds to `ResourcesProvider.loadFromTable` (plan 2.4).
 *
 * v1 recompiles and relinks everything on every call: correct-not-clever, and aapt2 on
 * a phone-sized res tree is fast enough for the ~0.3 s tier-0 budget (plan 2.3).
 */
class Aapt2Link(
	private val aapt2: File,
	private val androidJar: File,
) {
	sealed interface Result {
		data class Success(
			val resourcesArsc: File,
		) : Result

		data class Failed(
			val diagnostics: List<Diagnostic>,
		) : Result
	}

	fun relink(
		resDirs: List<File>,
		manifest: File,
		workDir: File,
	): Result {
		val compiledDir = File(workDir, "res-compiled")
		compiledDir.deleteRecursively()
		compiledDir.mkdirs()

		for (resDir in resDirs) {
			val compileResult =
				run(listOf(aapt2.absolutePath, "compile", "--dir", resDir.absolutePath, "-o", compiledDir.absolutePath))
			if (compileResult.exitCode != 0) {
				return Result.Failed(parseDiagnostics(compileResult.output, "aapt2 compile failed"))
			}
		}

		val flatFiles = compiledDir.listFiles { file -> file.name.endsWith(".flat") }.orEmpty()
		val linkedApk = File(workDir, "linked-res.apk")
		linkedApk.delete()
		val linkArguments =
			mutableListOf(
				aapt2.absolutePath,
				"link",
				"-o",
				linkedApk.absolutePath,
				"--manifest",
				manifest.absolutePath,
				"-I",
				androidJar.absolutePath,
				"--auto-add-overlay",
			)
		flatFiles.mapTo(linkArguments) { it.absolutePath }
		val linkResult = run(linkArguments)
		if (linkResult.exitCode != 0) {
			return Result.Failed(parseDiagnostics(linkResult.output, "aapt2 link failed"))
		}

		return try {
			Result.Success(extractArsc(linkedApk, workDir))
		} catch (e: Exception) {
			Result.Failed(
				listOf(Diagnostic(Diagnostic.Severity.ERROR, "extracting resources.arsc failed: ${e.message}")),
			)
		}
	}

	private fun extractArsc(
		linkedApk: File,
		workDir: File,
	): File {
		val out = File(workDir, "resources.arsc")
		ZipFile(linkedApk).use { zip ->
			val entry =
				zip.getEntry("resources.arsc")
					?: throw IllegalStateException("link output ${linkedApk.name} has no resources.arsc")
			zip.getInputStream(entry).use { input ->
				out.outputStream().use { output -> input.copyTo(output) }
			}
		}
		return out
	}

	private data class ProcessResult(
		val exitCode: Int,
		val output: String,
	)

	private fun run(command: List<String>): ProcessResult =
		try {
			// Merge stdout into stderr-side capture: aapt2 reports errors on stderr,
			// notes on stdout; the daemon's stdout stays protocol-only regardless.
			val process = ProcessBuilder(command).redirectErrorStream(true).start()
			val output = process.inputStream.bufferedReader().readText()
			val exitCode = process.waitFor()
			ProcessResult(exitCode, output)
		} catch (e: Exception) {
			ProcessResult(-1, "failed to run ${command.firstOrNull()}: ${e.message}")
		}

	// aapt2 messages look like "<path>:<line>: error: <msg>" or "error: <msg>".
	private val aapt2Line = Regex("""^(?:(.+?):(?:(\d+):)?\s*)?(error|warn(?:ing)?):\s*(.*)$""")

	private fun parseDiagnostics(
		output: String,
		fallback: String,
	): List<Diagnostic> {
		val diagnostics =
			output
				.lineSequence()
				.mapNotNull { line ->
					val match = aapt2Line.find(line.trim()) ?: return@mapNotNull null
					val (file, lineNumber, severity, message) = match.destructured
					Diagnostic(
						severity = if (severity.startsWith("warn")) Diagnostic.Severity.WARNING else Diagnostic.Severity.ERROR,
						message = message,
						file = file.ifEmpty { null },
						line = lineNumber.toIntOrNull(),
					)
				}.toList()
		if (diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) return diagnostics
		return diagnostics + Diagnostic(Diagnostic.Severity.ERROR, "$fallback: ${output.trim().take(2000)}")
	}
}
