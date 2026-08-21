package org.appdevforall.cotg.quickbuild.daemon.res

import org.appdevforall.cotg.quickbuild.protocol.Diagnostic
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Rebuilds the app's resource apk with the device-provisioned aapt2 after a resource edit:
 * compiles every res dir to `.flat`, then links them against android.jar with the proxy app
 * manifest. Every call recompiles and relinks everything, which costs single-digit seconds on a
 * phone-sized res tree (see [DEFAULT_TIMEOUT_MILLIS]).
 *
 * The payload is the whole linked apk, not a bare extracted table: `ResourcesProvider.loadFromTable`
 * (API 30+) and the API 28/29 addAssetPath shim both need a file-typed resource's bytes reachable
 * from the same archive as the table, so a stripped arsc throws `Resources$NotFoundException` on
 * the next activity recreate.
 *
 * A relink links a strict subset of what the proxy app build's resource merge produced (library
 * AAR resources are absent), so three rules keep it safe:
 *
 *  1. **[stableIds] is mandatory.** aapt2 assigns type ids by declaration order, so a type absent
 *     here shifts every later type down, and the proxy app's manifest still encodes `android:icon`
 *     as a fixed numeric id against the baseline table. `--stable-ids` pins each resource to the
 *     id AGP gave it.
 *
 *  2. **[libraryResources] must carry both of AGP's library-resource mechanisms.** VALUES
 *     resources are flattened transitively into the project's own `intermediates/merged_res/`;
 *     FILE-based ones are not, each library being compiled separately under
 *     `AndroidArtifacts.ArtifactType.COMPILED_DEPENDENCIES_RESOURCES`. A theme's item values
 *     reference both kinds, so either piece missing on its own fails the link.
 *     `--auto-add-overlay` does not help: it only relaxes duplicate checks among the caller's
 *     own inputs.
 *
 *  3. **The freshly compiled project resources go in as `-R`, ordered last.** A bare positional
 *     input always loses to any `-R` input for the same resource whatever the command-line order,
 *     and only among `-R` inputs does textual order decide - so passing the fresh compile
 *     positionally would serve merged_res's build-time value for every resource just edited.
 *
 * @property aapt2 the device-provisioned aapt2 binary, run as a subprocess; must be executable.
 * @property androidJar the platform jar, passed to every link as `-I`.
 * @property timeoutMillis per-invocation ceiling; an aapt2 that outlasts it is killed and the
 *   relink fails, and it is injectable so the timeout path is testable in milliseconds.
 */
class Aapt2Link(
	private val aapt2: File,
	private val androidJar: File,
	private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
	companion object {
		/**
		 * Two minutes per aapt2 invocation. A relink's aapt2 phases cost single-digit seconds on
		 * a phone-sized res tree [measured on a56, ADFA-4128], so this is ~20x headroom for a
		 * throttled 2 GB device, while staying under the client's 300 s per-request ceiling
		 * (`DaemonProcessClient.DEFAULT_REQUEST_TIMEOUT_MILLIS`) - the daemon has to free itself
		 * before the client gives up, or the next request meets a still-wedged daemon.
		 */
		const val DEFAULT_TIMEOUT_MILLIS = 120_000L
	}

	/** Outcome of one relink. */
	sealed interface Result {
		/**
		 * aapt2 linked a resource apk, with the timings the two phases cost.
		 *
		 * @property resourceApk the whole linked apk, verified to contain a `resources.arsc`;
		 *   this is the payload, not a bare table (see class KDoc).
		 * @property compileMillis wall time of the per-dir `aapt2 compile` loop.
		 * @property linkMillis wall time of the `aapt2 link` run.
		 */
		data class Success(
			val resourceApk: File,
			val compileMillis: Long = 0,
			val linkMillis: Long = 0,
		) : Result

		/**
		 * The relink did not produce a usable apk.
		 *
		 * @property diagnostics aapt2's own messages where they parsed, and always at least one
		 *   ERROR - a non-zero exit never reports clean.
		 */
		data class Failed(
			val diagnostics: List<Diagnostic>,
		) : Result
	}

	/**
	 * Compiles [resDirs] and links the result into a fresh resource apk under [workDir].
	 *
	 * @param resDirs the project's own `res/` roots, each compiled whole; empty means the link
	 *   carries only [libraryResources].
	 * @param manifest the proxy app's manifest, already compiled against the baseline table -
	 *   which is why [stableIds] matters (see class KDoc, rule 1).
	 * @param workDir the daemon-owned scratch dir; its `res-compiled` subdir is wiped on every
	 *   call and `linked-res.apk` is overwritten.
	 * @param stableIds AGP's `stableIds.txt` mapping (`pkg:type/name = 0x7f0xxxxx`) from the proxy
	 *   app build, passed as `--stable-ids` when readable; null falls back to unpinned
	 *   declaration-order ids (see class KDoc).
	 * @param libraryResources pre-compiled `.flat` units from the proxy app build - the
	 *   `intermediates/merged_res/` closure plus each AAR's separately-compiled file-based
	 *   resources - without which a library-provided reference fails to link (see class KDoc).
	 * @return [Result.Failed] when the scratch dir could not be reset, when either aapt2 phase
	 *   exited non-zero, or when the output carries no resource table.
	 */
	fun relink(
		resDirs: List<File>,
		manifest: File,
		workDir: File,
		stableIds: File? = null,
		libraryResources: List<File> = emptyList(),
	): Result {
		// The compiled dir must start empty: the link globs every .flat in it, so a leftover
		// from a previous run - a since-deleted resource's .flat, say - would be linked in as
		// a stale resource. A failed reset therefore fails the relink.
		val compiledDir = File(workDir, "res-compiled")
		if (!compiledDir.deleteRecursively() && compiledDir.listFiles()?.isNotEmpty() == true) {
			return Result.Failed(
				listOf(
					Diagnostic(
						Diagnostic.Severity.ERROR,
						"failed to clear compiled-resource dir ${compiledDir.absolutePath}; " +
							"leftover entries would leak stale .flat files into the link",
					),
				),
			)
		}
		if (!compiledDir.mkdirs() && !compiledDir.isDirectory) {
			return Result.Failed(
				listOf(
					Diagnostic(
						Diagnostic.Severity.ERROR,
						"failed to create compiled-resource dir ${compiledDir.absolutePath}",
					),
				),
			)
		}

		val compileStartedAt = System.currentTimeMillis()
		for (resDir in resDirs) {
			val compileResult =
				run(listOf(aapt2.absolutePath, "compile", "--dir", resDir.absolutePath, "-o", compiledDir.absolutePath))
			if (compileResult.exitCode != 0) {
				return Result.Failed(parseDiagnostics(compileResult.output, "aapt2 compile failed"))
			}
		}
		val compileMillis = System.currentTimeMillis() - compileStartedAt

		val flatFiles = compiledDir.listFiles { file -> file.name.endsWith(".flat") }.orEmpty()
		val linkedApk = File(workDir, "linked-res.apk")
		linkedApk.delete()
		val linkArguments = buildLinkArguments(linkedApk, manifest, flatFiles.toList(), stableIds, libraryResources)
		val linkStartedAt = System.currentTimeMillis()
		val linkResult = run(linkArguments)
		val linkMillis = System.currentTimeMillis() - linkStartedAt
		if (linkResult.exitCode != 0) {
			return Result.Failed(parseDiagnostics(linkResult.output, "aapt2 link failed"))
		}

		return try {
			Result.Success(verifyHasTable(linkedApk), compileMillis = compileMillis, linkMillis = linkMillis)
		} catch (e: Exception) {
			Result.Failed(
				listOf(Diagnostic(Diagnostic.Severity.ERROR, "linked apk has no resources.arsc: ${e.message}")),
			)
		}
	}

	/**
	 * Assembles the `aapt2 link` command line, with every resource input passed as `-R` and
	 * [flatFiles] last so the user's fresh edit wins over the baseline (see class KDoc, rule 3).
	 * `internal` rather than private so the `--stable-ids` behavior is unit-testable without an
	 * aapt2 binary on the test host, unlike [relink] itself.
	 *
	 * @param linkedApk the `-o` target; not created here, only named.
	 * @param manifest the proxy app's manifest, passed verbatim as `--manifest`; neither read
	 *   nor rewritten here.
	 * @param flatFiles this run's freshly compiled `.flat` units, appended last so they win.
	 * @param stableIds null, or a path that does not exist, omits `--stable-ids` entirely.
	 * @param libraryResources baseline `-R` inputs, emitted ahead of [flatFiles].
	 * @return the full argv, aapt2's own path included as element 0.
	 */
	internal fun buildLinkArguments(
		linkedApk: File,
		manifest: File,
		flatFiles: List<File>,
		stableIds: File?,
		libraryResources: List<File> = emptyList(),
	): List<String> {
		val arguments =
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
		if (stableIds != null && stableIds.isFile) {
			arguments += listOf("--stable-ids", stableIds.absolutePath)
		}
		libraryResources.forEach { arguments += listOf("-R", it.absolutePath) }
		flatFiles.forEach { arguments += listOf("-R", it.absolutePath) }
		return arguments
	}

	/**
	 * Checks that [linkedApk] actually contains a resource table before it ships as the
	 * payload - a missing entry means aapt2 produced malformed output despite exit 0. Entry
	 * lookup only, no extraction.
	 *
	 * @param linkedApk aapt2's link output, already known to have exited 0.
	 * @return [linkedApk] unchanged, so the check reads inline at the call site.
	 * @throws IllegalStateException when the archive holds no `resources.arsc`; [relink] turns
	 *   it, and any zip-level failure, into a [Result.Failed].
	 */
	private fun verifyHasTable(linkedApk: File): File {
		ZipFile(linkedApk).use { zip ->
			zip.getEntry("resources.arsc")
				?: throw IllegalStateException("link output ${linkedApk.name} has no resources.arsc")
		}
		return linkedApk
	}

	private data class ProcessResult(
		val exitCode: Int,
		val output: String,
	)

	/**
	 * Runs an aapt2 command, capturing its merged output; a launch failure becomes exit -1.
	 *
	 * The output is drained to EOF before the exit code is waited on, since aapt2 can outrun the
	 * pipe buffer and waiting first would deadlock against a full pipe. That drain is itself
	 * unbounded, so a wedged aapt2 would stop the single-threaded daemon loop from answering ANY
	 * request, `ping` and `shutdown` included - hence the watchdog, which kills the child at
	 * [timeoutMillis] and thereby closes the pipe and releases the read.
	 *
	 * @param command the full argv, executable first; run to completion, so the caller blocks.
	 * @return the exit code and the merged stdout/stderr text, never null and never thrown; a
	 *   timeout reports exit -1 with a message [parseDiagnostics] renders as an ERROR.
	 */
	private fun run(command: List<String>): ProcessResult {
		val process =
			try {
				// aapt2 reports errors on stderr and notes on stdout, so both are captured
				// together. The daemon's own stdout stays protocol-only either way.
				ProcessBuilder(command).redirectErrorStream(true).start()
			} catch (e: Exception) {
				return ProcessResult(-1, "failed to run ${command.firstOrNull()}: ${e.message}")
			}
		val timedOut = AtomicBoolean(false)
		// Daemon thread, so a watchdog still waiting cannot hold up JVM exit. It ends on its
		// own as soon as the child does, so nothing interrupts it.
		Thread {
			if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
				timedOut.set(true)
				process.destroyForcibly()
			}
		}.apply {
			isDaemon = true
			name = "aapt2-watchdog"
			start()
		}
		return try {
			val output = process.inputStream.bufferedReader().use { it.readText() }
			val exitCode = process.waitFor()
			if (timedOut.get()) {
				ProcessResult(-1, "aapt2 timed out after $timeoutMillis ms and was killed: ${command.joinToString(" ")}")
			} else {
				ProcessResult(exitCode, output)
			}
		} catch (e: Exception) {
			ProcessResult(-1, "failed to run ${command.firstOrNull()}: ${e.message}")
		} finally {
			// A failure on the read path must not orphan the child.
			process.destroy()
		}
	}

	// aapt2 messages look like "<path>:<line>: error: <msg>" or "error: <msg>".
	private val aapt2Line = Regex("""^(?:(.+?):(?:(\d+):)?\s*)?(error|warn(?:ing)?):\s*(.*)$""")

	/**
	 * Parses aapt2's output into diagnostics, appending a [fallback] error carrying the raw
	 * output when nothing in it parsed as an error - a non-zero exit must never report clean.
	 *
	 * @param output aapt2's merged stdout/stderr, parsed line by line; unrecognized lines drop.
	 * @param fallback prefix for the synthesized error, naming which phase failed.
	 * @return at least one ERROR diagnostic; the fallback carries the raw output, truncated to
	 *   2000 characters.
	 */
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
