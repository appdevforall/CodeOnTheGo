package org.appdevforall.cotg.quickbuild.daemon.res

import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import java.io.File
import java.util.zip.ZipFile

/**
 * Shells the device-provisioned aapt2 to rebuild the resource table after a res-only
 * (or mixed) save: compile every res dir to .flat files, then link them against
 * android.jar with the proxy app manifest. The payload the runtime applies is the WHOLE
 * linked apk (`resources.arsc` plus every compiled resource FILE - layouts, drawable
 * XMLs, adaptive-icon XMLs, ...), not a bare extracted table.
 *
 * A bare table cannot back a file-typed resource: `ResourcesProvider.loadFromTable`
 * (API 30+) and the API 28/29 addAssetPath shim both need the file bytes reachable from
 * the SAME archive the table came from. Ship a stripped arsc and an unrelated file-backed
 * resource the app touches on every activity recreate - `ic_launcher.xml`, say - throws
 * `Resources$NotFoundException` after a `strings.xml`-only edit.
 *
 * v1 recompiles and relinks everything on every call: correct-not-clever, and aapt2 on
 * a phone-sized res tree is fast enough for the ~0.3 s tier-0 budget.
 *
 * Three rules make a relink safe, because it links a strict SUBSET of what the proxy app
 * build's Gradle resource-merge produced (library AAR resources are absent):
 *
 *  1. **[stableIds] is mandatory.** aapt2 assigns type ids by declaration order, so a
 *     resource TYPE present in the baseline but absent here shifts every later type down
 *     (`mipmap` 04 -> 03). The proxy app's manifest was compiled once against the BASELINE
 *     table and still encodes `android:icon` as a fixed numeric id, which now resolves to
 *     the wrong type and crashes on recreate. `--stable-ids` pins each resource to the id
 *     AGP gave it.
 *
 *  2. **[libraryResources] must carry BOTH of AGP's library-resource mechanisms.** A
 *     relink cannot resolve a name the project's own res/ never declares - e.g. a
 *     Material3-themed template extending `Theme.Material3.DayNight.NoActionBar`.
 *     `--auto-add-overlay` does not help; it only relaxes duplicate checks between the
 *     caller's own inputs.
 *      - VALUES resources (styles/themes/colors/dimens/strings/attrs, and everything a
 *        style parents against) are flattened TRANSITIVELY into the project's own
 *        `intermediates/merged_res/` by AGP's classic resource merger.
 *      - FILE-based resources (layouts, drawables, anims, menus, ...) are NOT in
 *        merged_res; each library is compiled separately by an `ArtifactTransform` keyed
 *        on `AndroidArtifacts.ArtifactType.COMPILED_DEPENDENCIES_RESOURCES`, one `.flat`
 *        per resource.
 *     A theme's own item values reference both kinds (`popupMenuBackground` points at a
 *     drawable), so either piece missing on its own still fails the link.
 *
 *  3. **[flatFiles] goes in as `-R`, ordered LAST among all `-R` args.** aapt2's real
 *     precedence is not the docs' "the last conflicting resource given takes precedence":
 *     a bare positional input ALWAYS loses to ANY `-R` input for the same resource,
 *     whatever the command-line order; only among multiple `-R` inputs does textual order
 *     decide. merged_res carries the project's own resources too, from proxy app build
 *     time - so passing the fresh `aapt2 compile --dir res` output positionally serves
 *     the STALE value for every resource the user just edited.
 */
class Aapt2Link(
	private val aapt2: File,
	private val androidJar: File,
) {
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
	 * @param stableIds AGP's `stableIds.txt` mapping (`pkg:type/name = 0x7f0xxxxx`) from
	 *   the proxy app build's real resource processing, if CoGo has one for this project.
	 *   When present and readable, passed to aapt2 as `--stable-ids` (see class KDoc for
	 *   why). Null falls back to aapt2's own declaration-order id assignment, unpinned.
	 * @param libraryResources pre-compiled `.flat` resource units the proxy app build's real
	 *   AGP resource processing produced (the project's own `intermediates/merged_res/`
	 *   closure - transitively including every dependency AAR's VALUES resources - plus
	 *   each resource-providing AAR's separately-compiled FILE-based resources). Feeds a
	 *   resource an AAR declares (e.g. Material3's `Theme.Material3.DayNight.NoActionBar`)
	 *   back into the relink so it resolves. Empty leaves only the
	 *   project's own fresh `resDirs` compile visible, so any library-provided reference
	 *   fails linking. Order matters - see class KDoc.
	 */
	fun relink(
		resDirs: List<File>,
		manifest: File,
		workDir: File,
		stableIds: File? = null,
		libraryResources: List<File> = emptyList(),
	): Result {
		// The compiled dir must start EMPTY: relink globs every .flat in it, so a leftover
		// from a previous run (e.g. a since-deleted resource's .flat) would be swept into
		// the link as a stale resource. A failed reset therefore fails the relink.
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
	 * Assembles the `aapt2 link` command line. `internal` (not private) so its
	 * `--stable-ids` behavior is unit-testable without an aapt2 binary on the test host -
	 * unlike [relink] itself, which needs a real toolchain end to end (`@EnabledIf`
	 * `Aapt2LinkTest`).
	 *
	 * Every resource input is passed as `-R` (none bare-positional): a bare positional
	 * input always LOSES to an `-R` overlay for the same resource regardless of textual
	 * order, so mixing the two would make [libraryResources] (which
	 * can carry a stale project-owned copy of a resource, via merged_res) win over
	 * [flatFiles]'s fresh edit if flatFiles were positional. Passing everything as `-R`,
	 * with [flatFiles] LAST, makes ordering do what "last one wins" actually promises:
	 * [libraryResources] first (baseline + library resources), [flatFiles] last (today's
	 * live edit wins on any conflict with the baseline).
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
	 * Cheap sanity check (entry lookup, no extraction) before shipping [linkedApk] as-is:
	 * the whole apk is the payload now (see class doc), so a missing table entry would
	 * mean aapt2 produced a malformed output despite exit 0 - fail loudly rather than
	 * ship a resource apk the runtime cannot load.
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

	private fun run(command: List<String>): ProcessResult =
		try {
			// Merge stdout into stderr-side capture: aapt2 reports errors on stderr,
			// notes on stdout; the daemon's stdout stays protocol-only regardless.
			val process = ProcessBuilder(command).redirectErrorStream(true).start()
			val output = process.inputStream.bufferedReader().use { it.readText() }
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
