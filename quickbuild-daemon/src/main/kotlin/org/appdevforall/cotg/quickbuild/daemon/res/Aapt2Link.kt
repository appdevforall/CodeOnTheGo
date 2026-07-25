package org.appdevforall.cotg.quickbuild.daemon.res

import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import java.io.File
import java.util.zip.ZipFile

/**
 * Shells the device-provisioned aapt2 to rebuild the resource table after a res-only
 * (or mixed) save: compile every res dir to .flat files, then link them against
 * android.jar with the test-app manifest. The payload the runtime applies is the WHOLE
 * linked apk (`resources.arsc` plus every compiled resource FILE - layouts, drawable
 * XMLs, adaptive-icon XMLs, ...), not a bare extracted table.
 *
 * A bare table cannot back a file-typed resource: `ResourcesProvider.loadFromTable`
 * (API 30+) and the API 28/29 addAssetPath shim both need the actual file bytes
 * reachable from the SAME archive the table came from, and a table-only payload has no
 * such archive. ADFA-4128 Bug 5: a `strings.xml`-only edit shipped a stripped arsc: the
 * reload's activity recreate crashed with `Resources$NotFoundException` resolving
 * `res/mipmap-anydpi-v26/ic_launcher.xml` - a resource the edit never even touched, just
 * one this app happens to reference on every recreate. Shipping the full linked apk and
 * loading it with `ResourcesProvider.loadFromApk` / a direct `addAssetPath` (see
 * `ResourceStore`) resolves file-backed resources from the same archive as the table.
 *
 * v1 recompiles and relinks everything on every call: correct-not-clever, and aapt2 on
 * a phone-sized res tree is fast enough for the ~0.3 s tier-0 budget (plan 2.3).
 *
 * A relink links ONLY the project's own res/ - a strict subset of what the real setup
 * build's Gradle resource-merge produces (library AAR resources, e.g. CoGo's injected
 * LogSender `bool/logsender_enabled`, are absent). When a whole resource TYPE present in
 * the baseline link is absent here, aapt2's default (declaration-order) type-index
 * assignment shifts every type ordered after it - e.g. `mipmap` moves from the baseline's
 * type-id 04 to 03 - and the test app's `AndroidManifest.xml` (compiled once, at
 * setup-build time, against the BASELINE table) still encodes `android:icon` as the fixed
 * numeric id `0x7f040000`. Once the relinked table is live, that same id resolves against
 * type 04 in the NEW table, which is no longer mipmap - the OS resolves the app's icon to
 * the wrong resource type and crashes on activity recreate (ADFA-4128 Bug 6). [stableIds]
 * (`aapt2 link --stable-ids`) fixes this: it pins each resource present in the relink to
 * the exact numeric id AGP's own setup build gave it, so `mipmap` keeps type-id 04
 * regardless of what other types this narrower relink does or doesn't include.
 *
 * A relink also can't resolve any resource a dependency AAR provides - e.g. Material3's
 * `Theme.Material3.DayNight.NoActionBar`, which any Material3-themed template's own
 * `themes.xml` extends - because the project's own res/ never declares it (ADFA-4128
 * Bug 8). `--auto-add-overlay` doesn't help: it only relaxes duplicate-resource checks
 * between the caller's OWN inputs, it can't summon a name the inputs never contain.
 * AGP resolves library resources through two SEPARATE mechanisms the real setup build
 * runs and this relink must feed back in via [libraryResources]:
 *  - VALUES resources (styles/themes/colors/dimens/strings/attrs, and everything a style
 *    parents against) are flattened, TRANSITIVELY, across the whole dependency graph by
 *    AGP's classic resource merger into the project's OWN `intermediates/merged_res/`
 *    tree - confirmed on-host by grepping a real `mergeDebugResources` run's `--info`
 *    log: it compiles `intermediates/incremental/.../merged.dir/values/values.xml`, a
 *    file that contains the literal `Theme.Material3.DayNight.NoActionBar` declaration
 *    though the project's own `res/values/` never does.
 *  - FILE-based resources (layouts, drawables, anims, menus, ...) are NOT merged into
 *    merged_res; each resource-providing library is compiled SEPARATELY via a Gradle
 *    `ArtifactTransform` keyed on the artifact-type attribute value
 *    `"android-compiled-dependencies-resources"` (`AndroidArtifacts.ArtifactType
 *    .COMPILED_DEPENDENCIES_RESOURCES`, confirmed by inspecting AGP's own
 *    `AndroidArtifacts$ArtifactType.class` constant pool), producing one `.flat` file per
 *    resource, cached per-dependency. A Material3 theme's OWN item values reference both
 *    kinds (e.g. `popupMenuBackground` points at a drawable) - EITHER piece missing on
 *    its own still fails linking, verified on-host by adding each independently.
 *
 * Ordering matters and is NOT what the aapt2 docs' "the last conflicting resource given
 * takes precedence" wording suggests in isolation: verified empirically (real aapt2, this
 * host) that a bare positional (non `-R`) input ALWAYS loses to ANY `-R` overlay input for
 * the same resource, regardless of which one is written first or last on the command
 * line - `-R` beats positional unconditionally; only among MULTIPLE `-R` inputs does
 * textual order decide (last `-R` wins). [flatFiles] (the project's OWN fresh
 * `aapt2 compile --dir res` output) is therefore ALSO passed as `-R`, ordered LAST among
 * all `-R` arguments - so a resource the user just edited, which may also exist in
 * [libraryResources] (merged_res carries the project's own resources too, from
 * setup-build time), resolves to the FRESH edit, not the stale merged_res snapshot. This
 * is a real, previously-latent correctness bug: the original plan for this fix assumed
 * [flatFiles] as bare positional args would win "because it's listed last" - that is false
 * for aapt2's actual precedence rule, and would have made every conflicting edit silently
 * serve the stale setup-build value.
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
	 *   the setup build's real resource processing, if CoGo has one for this project.
	 *   When present and readable, passed to aapt2 as `--stable-ids` (see class KDoc for
	 *   why). Null falls back to the pre-fix behavior: aapt2's own declaration-order id
	 *   assignment, unpinned.
	 * @param libraryResources pre-compiled `.flat` resource units the setup build's real
	 *   AGP resource processing produced (the project's own `intermediates/merged_res/`
	 *   closure - transitively including every dependency AAR's VALUES resources - plus
	 *   each resource-providing AAR's separately-compiled FILE-based resources). Feeds a
	 *   resource an AAR declares (e.g. Material3's `Theme.Material3.DayNight.NoActionBar`)
	 *   back into the relink so it resolves (ADFA-4128 Bug 8). Empty falls back to the
	 *   pre-fix behavior: only the project's own fresh `resDirs` compile is visible, so
	 *   any library-provided reference fails linking. Order matters - see class KDoc.
	 */
	fun relink(
		resDirs: List<File>,
		manifest: File,
		workDir: File,
		stableIds: File? = null,
		libraryResources: List<File> = emptyList(),
	): Result {
		val compiledDir = File(workDir, "res-compiled")
		compiledDir.deleteRecursively()
		compiledDir.mkdirs()

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
	 * Every resource input is passed as `-R` (none bare-positional): verified empirically
	 * that a bare positional input always LOSES to an `-R` overlay for the same resource
	 * regardless of textual order, so mixing the two would make [libraryResources] (which
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
