package org.appdevforall.cotg.quickbuild.daemon.res

import org.appdevforall.cotg.quickbuild.protocol.Diagnostic
import java.io.File
import java.io.IOException
import java.io.Reader
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

		/** Bound on parsed diagnostics per link; same reason as DexTool's MAX_DIAGNOSTIC_CHARS. */
		private const val MAX_DIAGNOSTICS = 50

		/**
		 * Resource-input count above which the link arguments move into an `@argfile`. A
		 * Material/AndroidX app's library-resource closure runs to a few thousand `-R` pairs at
		 * ~120 bytes each, and bionic's exec argument budget is far below desktop Linux's 2 MiB,
		 * so a big link can cross ARG_MAX and die as an unhelpful "cannot run program". Small
		 * links stay inline, where they read directly in a log or a test. `internal` so the
		 * boundary is testable.
		 */
		internal const val ARGFILE_THRESHOLD = 100

		/** Name of the `@argfile` written beside the link output. */
		internal const val ARGFILE_NAME = "link-inputs.txt"

		/**
		 * Force-kills [process] when the watchdog's wait expired while it was still running.
		 *
		 * `Process.waitFor(timeout)` also returns false for a child that exited just after the
		 * wait expired, and `destroyForcibly` then no-ops - so the kill, not the wait, is what
		 * says a link was actually cut short. Without this check a link that finished is reported
		 * as timed out, which is a rare spurious relink failure and the hardest kind to diagnose.
		 *
		 * @param process the aapt2 child this run's watchdog guards.
		 * @param beforeKill runs after the liveness check and BEFORE the kill. The kill is what
		 *   closes the child's pipe and releases the request thread's output drain, so a timeout
		 *   flag stored after it can land after that thread has already read the flag - and a
		 *   link really cut short at the deadline is then reported as an ordinary link failure
		 *   with nothing naming the timeout. Ordering it here keeps the store ahead of the wake-up.
		 * @return true when a live process was killed here.
		 */
		internal fun killIfAlive(
			process: Process,
			beforeKill: () -> Unit = {},
		): Boolean {
			if (!process.isAlive) return false
			beforeKill()
			process.destroyForcibly()
			return true
		}

		/**
		 * The watchdog's verdict for one run: whether the link was actually cut short.
		 *
		 * Both halves matter and only together. An expired wait alone is not enough - it also
		 * returns false for a child that exited just after the deadline - so the verdict is the
		 * KILL, which [killIfAlive] only reports for a process that was still running. Held here
		 * rather than inline in the watchdog thread so the pairing is pinned by a test; the
		 * timing that produces the losing case cannot be reproduced with a real process.
		 *
		 * @param process the aapt2 child this run's watchdog guards.
		 * @param timeoutMillis how long the child is given before the kill.
		 * @param beforeKill see [killIfAlive]; the caller's timeout flag belongs here, not after
		 *   the return.
		 * @return true only when the wait expired AND a live process was killed.
		 */
		internal fun watchdogTimedOut(
			process: Process,
			timeoutMillis: Long,
			beforeKill: () -> Unit = {},
		): Boolean = !process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) && killIfAlive(process, beforeKill)

		/**
		 * Reads [reader] to EOF, keeping at most [maxChars] of it.
		 *
		 * The rest is drained and dropped rather than left in the pipe: a child blocked on a full
		 * pipe never exits, and the watchdog would then kill a process that had only been
		 * talkative. The bound exists because every other step in this path is bounded
		 * ([parseDiagnostics] keeps 50 entries and a 2000-char fallback) and the read that feeds
		 * them was the one place a pathological aapt2 could grow the daemon's heap on a 2-4 GB
		 * phone without limit. A truncated read ends with a marker naming how much was dropped.
		 *
		 * @param reader the child's merged output; read to EOF and not closed here.
		 * @param maxChars how many characters to retain before the marker.
		 * @return the retained output, plus the marker when anything was dropped.
		 */
		internal fun readBounded(
			reader: Reader,
			maxChars: Int,
		): String {
			val kept = StringBuilder()
			val buffer = CharArray(8192)
			var dropped = 0L
			while (true) {
				val n = reader.read(buffer)
				if (n < 0) break
				val room = maxChars - kept.length
				if (room >= n) {
					kept.appendRange(buffer, 0, n)
				} else {
					if (room > 0) kept.appendRange(buffer, 0, room)
					dropped += n - maxOf(room, 0)
				}
			}
			if (dropped > 0) kept.append("\n[aapt2 output truncated: $dropped more characters dropped]")
			return kept.toString()
		}

		/**
		 * Characters of aapt2 output retained per run. A phone-sized res tree's link produces a
		 * few hundred bytes of notes on success and one line per broken resource on failure, so
		 * 256K holds thousands of diagnostics - far past what [parseDiagnostics] keeps.
		 */
		internal const val MAX_OUTPUT_CHARS = 256 * 1024
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
	 *   app build, passed as `--stable-ids`. A non-null path that is missing on disk FAILS the
	 *   relink (see class KDoc, rule 1); only an explicit null links unpinned,
	 *   declaration-order ids.
	 * @param libraryResources pre-compiled `.flat` units from the proxy app build - the
	 *   `intermediates/merged_res/` closure plus each AAR's separately-compiled file-based
	 *   resources - without which a library-provided reference fails to link (see class KDoc).
	 * @return [Result.Failed] when more than one [resDirs] entry is given, when a named
	 *   [stableIds] file is absent, when the scratch dir could not be reset, when either aapt2
	 *   phase exited non-zero, or when the output carries no resource table.
	 */
	fun relink(
		resDirs: List<File>,
		manifest: File,
		workDir: File,
		stableIds: File? = null,
		libraryResources: List<File> = emptyList(),
	): Result {
		// aapt2 derives each .flat name from the resource's path WITHIN ITS ROOT, and every root
		// here compiles into one -o dir, so two roots holding layout/main.xml both write
		// layout_main.xml.flat and the last one silently wins. Unreachable today -
		// QuickBuildProjectLayout.resDirs() returns exactly src/main/res - but the protocol
		// advertises a List and the day a flavor or build-type res root is added the symptom is
		// "my string change did not take", with no error. Fail loudly instead, so extending
		// resDirs() turns this red rather than quiet.
		if (resDirs.size > 1) {
			return Result.Failed(
				listOf(
					Diagnostic(
						Diagnostic.Severity.ERROR,
						"quick build supports one resource root, got ${resDirs.size}: " +
							resDirs.joinToString { it.absolutePath } +
							" - compiling several into one dir lets same-named resources overwrite each other",
					),
				),
			)
		}
		// Rule 1 makes stable-ids mandatory whenever the session has one. A named-but-missing
		// file (a stale or moved AGP intermediate path) must not silently degrade to an unpinned
		// link: that exits 0 and only fails ON DEVICE, as a crash or the wrong resource, with
		// nothing in the daemon log distinguishing it from a pinned link. Only an explicit null
		// - no stable-ids known at all - may link unpinned.
		if (stableIds != null && !stableIds.isFile) {
			return Result.Failed(
				listOf(
					Diagnostic(
						Diagnostic.Severity.ERROR,
						"stable-ids file is missing: ${stableIds.absolutePath} - refusing to relink " +
							"unpinned, which would let resource ids shift under the installed baseline",
					),
				),
			)
		}
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
		val linkArguments =
			try {
				buildLinkArguments(linkedApk, manifest, flatFiles.toList(), stableIds, libraryResources)
			} catch (e: IOException) {
				return Result.Failed(
					listOf(Diagnostic(Diagnostic.Severity.ERROR, "failed to write aapt2 argfile: ${e.message}")),
				)
			}
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
	 * @param stableIds null omits `--stable-ids` entirely; a missing path is omitted too, as
	 *   defense in depth, but [relink] fails a named-but-missing file before reaching here.
	 * @param libraryResources baseline `-R` inputs, emitted ahead of [flatFiles].
	 * @return the full argv, aapt2's own path included as element 0. Past [ARGFILE_THRESHOLD]
	 *   resource inputs, the whole input list moves into an `@argfile` next to [linkedApk],
	 *   passed as a single `-R @file`. aapt2 expands the file into its whitespace-split paths
	 *   (flags cannot ride along - it rejects them as "missing required flag -o") and every
	 *   entry keeps `-R` overlay semantics in file order. Whitespace has no escape in that
	 *   format, so an input path containing any keeps the inline `-R` pairs whatever the
	 *   count: the project directory reaches these paths unsanitised, and the default new
	 *   project is called "My Application". Both halves of the expansion are pinned by the
	 *   argfile relink test.
	 *
	 *   That fallback is safe because the argv budget is not reachable at real project sizes
	 *   [measured 2026-09-03 on a macOS host]: a Material/AndroidX corpus app links 292
	 *   resource inputs, ~46 KB of argv once re-rooted on a device project path, and CoGo's own
	 *   app module - far larger than anything Quick Build targets - links 1475, ~229 KB. The
	 *   host's measured exec ceiling for the same paths is 5990 `-R` pairs (~946 KB, its 1 MiB
	 *   ARG_MAX); Android's is bionic's `RLIMIT_STACK/4`, ~2 MiB on the 8 MiB default. So the
	 *   worst real app has ~4x headroom against the tighter of the two and a normal one ~20x,
	 *   and the argfile is a size optimisation rather than a guard whose loss endangers a
	 *   default-named project. Staging whitespace-free symlinks would buy nothing measurable.
	 * @throws IOException when the argfile cannot be written; [relink] turns that into a
	 *   [Result.Failed].
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
		val resourceInputs = libraryResources + flatFiles
		val argfile = File(linkedApk.absoluteFile.parentFile, ARGFILE_NAME)
		if (resourceInputs.size <= ARGFILE_THRESHOLD || resourceInputs.any(::hasWhitespace)) {
			// TODO(ADFA-XXXXX): a whitespace path forces the inline form no matter how many
			// inputs, so an app several times larger than CoGo's own app module under a path like
			// "My Application" would exceed the argv limit and fail the link loudly (E2BIG surfaces
			// as Result.Failed). The fix is to stage the inputs under whitespace-free names and
			// always use the argfile - as copies, not symlinks, since the phone's emulated storage
			// refuses to create a symlink. Deferred until an app that size builds on a phone.
			// A previous link may have left one behind; it is stale the moment the inputs
			// change, and nothing else deletes it.
			argfile.delete()
			resourceInputs.forEach { arguments += listOf("-R", it.absolutePath) }
			return arguments
		}
		argfile.writeText(resourceInputs.joinToString("\n") { it.absolutePath })
		arguments += listOf("-R", "@${argfile.absolutePath}")
		return arguments
	}

	/**
	 * Whether [file]'s absolute path holds whitespace, which the argfile format cannot carry.
	 *
	 * @param file one resource input, named by its absolute path in the argv either way.
	 * @return true when the path must be passed inline as its own `-R` argument.
	 */
	private fun hasWhitespace(file: File): Boolean = file.absolutePath.any { it.isWhitespace() }

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
	 * pipe buffer and waiting first would deadlock against a full pipe. That drain has no time
	 * bound, so a wedged aapt2 would stop the single-threaded daemon loop from answering ANY
	 * request, `ping` and `shutdown` included - hence the watchdog, which kills the child at
	 * [timeoutMillis] and thereby closes the pipe and releases the read. Its size is bounded by
	 * [readBounded], so a flooding aapt2 costs at most [MAX_OUTPUT_CHARS] of heap.
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
		// own as soon as the child does, so nothing interrupts it. The flag is stored before
		// the kill (see killIfAlive), since the kill is what wakes the read below.
		Thread {
			watchdogTimedOut(process, timeoutMillis) { timedOut.set(true) }
		}.apply {
			isDaemon = true
			name = "aapt2-watchdog"
			start()
		}
		return try {
			val output = process.inputStream.bufferedReader().use { readBounded(it, MAX_OUTPUT_CHARS) }
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
	 * @return at least one ERROR diagnostic; capped at [MAX_DIAGNOSTICS] entries plus a
	 *   "+K more" marker (a broken resource pass can name every file in the project, and the
	 *   whole list rides a protocol line into a phone-screen panel - same rationale as
	 *   DexTool's MAX_DIAGNOSTIC_CHARS). The fallback carries the raw output, truncated to
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
		if (diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) return diagnostics.capped()
		return diagnostics.capped() +
			Diagnostic(Diagnostic.Severity.ERROR, "$fallback: ${output.trim().take(2000)}")
	}

	/** First [MAX_DIAGNOSTICS] entries, plus one marker naming how many were elided. */
	private fun List<Diagnostic>.capped(): List<Diagnostic> {
		if (size <= MAX_DIAGNOSTICS) return this
		return take(MAX_DIAGNOSTICS) +
			Diagnostic(Diagnostic.Severity.ERROR, "+${size - MAX_DIAGNOSTICS} more aapt2 diagnostics elided")
	}
}
