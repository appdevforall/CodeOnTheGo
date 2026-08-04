package org.appdevforall.cotg.quickbuild.daemon.res

import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import java.io.File
import java.util.zip.ZipFile

/**
 * Rebuilds the app's resource apk with the device-provisioned aapt2 after a resource edit:
 * compiles every res dir to `.flat` files, then links them against android.jar with the proxy
 * app manifest.
 *
 * The payload the runtime applies is the whole linked apk - `resources.arsc` plus every
 * compiled resource file - not a bare extracted table. A bare table cannot back a file-typed
 * resource: `ResourcesProvider.loadFromTable` (API 30+) and the API 28/29 addAssetPath shim
 * both need the file bytes reachable from the same archive the table came from, so a stripped
 * arsc throws `Resources$NotFoundException` on the next activity recreate.
 *
 * v1 recompiles and relinks everything on every call; aapt2 on a phone-sized res tree is fast
 * enough for the tier-0 budget.
 *
 * A relink links a strict subset of what the proxy app build's resource merge produced (library
 * AAR resources are absent), so three rules keep it safe:
 *
 *  1. **[stableIds] is mandatory.** aapt2 assigns type ids by declaration order, so a resource
 *     type present in the baseline but absent here shifts every later type down (`mipmap`
 *     04 -> 03). The proxy app's manifest was compiled once against the baseline table and
 *     still encodes `android:icon` as a fixed numeric id, which would then resolve to the wrong
 *     type and crash on recreate. `--stable-ids` pins each resource to the id AGP gave it.
 *
 *  2. **[libraryResources] must carry both of AGP's library-resource mechanisms**, or a relink
 *     cannot resolve a name the project's own res/ never declares (a template extending
 *     `Theme.Material3.DayNight.NoActionBar`, say). VALUES resources - styles, themes, colors,
 *     dimens, strings, attrs, and anything a style parents against - are flattened transitively
 *     into the project's own `intermediates/merged_res/`. FILE-based resources - layouts,
 *     drawables, anims, menus - are not; each library is compiled separately by an
 *     `ArtifactTransform` keyed on `AndroidArtifacts.ArtifactType.COMPILED_DEPENDENCIES_RESOURCES`.
 *     A theme's item values reference both kinds, so either piece missing on its own fails the
 *     link. `--auto-add-overlay` does not help: it only relaxes duplicate checks among the
 *     caller's own inputs.
 *
 *  3. **The freshly compiled project resources go in as `-R`, ordered last among the `-R`
 *     args.** aapt2's real precedence is not the docs' "the last conflicting resource wins":
 *     a bare positional input always loses to any `-R` input for the same resource whatever the
 *     command-line order, and only among `-R` inputs does textual order decide. merged_res
 *     carries the project's own resources from proxy app build time, so passing the fresh
 *     compile positionally would serve the stale value for every resource the user just edited.
 */
class Aapt2Link(
	private val aapt2: File,
	private val androidJar: File,
) {
	/** Outcome of one relink. */
	sealed interface Result {
		/**
		 * @property compileMillis wall time of the per-dir `aapt2 compile` loop.
		 * @property linkMillis wall time of the `aapt2 link` run.
		 */
		data class Success(
			val resourceApk: File,
			val compileMillis: Long = 0,
			val linkMillis: Long = 0,
		) : Result

		data class Failed(
			val diagnostics: List<Diagnostic>,
		) : Result
	}

	/**
	 * Compiles [resDirs] and links the result into a fresh resource apk under [workDir].
	 *
	 * @param stableIds AGP's `stableIds.txt` mapping (`pkg:type/name = 0x7f0xxxxx`) from the
	 *   proxy app build, if CoGo has one for this project. Passed to aapt2 as `--stable-ids`
	 *   when readable; null falls back to unpinned declaration-order ids (see class KDoc).
	 * @param libraryResources pre-compiled `.flat` units from the proxy app build's AGP
	 *   resource processing - the `intermediates/merged_res/` closure plus each AAR's
	 *   separately-compiled file-based resources. Empty means any library-provided reference
	 *   fails to link (see class KDoc).
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

	/** Runs an aapt2 command, capturing its merged output; a launch failure becomes exit -1. */
	private fun run(command: List<String>): ProcessResult =
		try {
			// aapt2 reports errors on stderr and notes on stdout, so both are captured
			// together. The daemon's own stdout stays protocol-only either way.
			val process = ProcessBuilder(command).redirectErrorStream(true).start()
			val output = process.inputStream.bufferedReader().use { it.readText() }
			val exitCode = process.waitFor()
			ProcessResult(exitCode, output)
		} catch (e: Exception) {
			ProcessResult(-1, "failed to run ${command.firstOrNull()}: ${e.message}")
		}

	// aapt2 messages look like "<path>:<line>: error: <msg>" or "error: <msg>".
	private val aapt2Line = Regex("""^(?:(.+?):(?:(\d+):)?\s*)?(error|warn(?:ing)?):\s*(.*)$""")

	/**
	 * Parses aapt2's output into diagnostics, appending a [fallback] error carrying the raw
	 * output when nothing in it parsed as an error - a non-zero exit must never report clean.
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
