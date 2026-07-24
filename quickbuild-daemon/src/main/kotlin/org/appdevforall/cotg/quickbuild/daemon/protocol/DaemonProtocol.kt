package org.appdevforall.cotg.quickbuild.daemon.protocol

/**
 * The daemon wire protocol model (quick-build/README.md "Daemon protocol"). One
 * line-delimited JSON request at a time over stdin, one JSON response line over stdout.
 * These types are pure data so the codec and router unit-test on the JVM without any
 * process plumbing.
 */
sealed interface DaemonRequest {
	val id: Long
}

/**
 * One-time session setup. Everything path-shaped arrives absolute from CoGo.
 *
 * @property projectRoot the user project root (identifies the session; not written to).
 * @property classpath compile classpath jars (android.jar, kotlin-stdlib, library jars).
 *   Fixed for the session - the compiler snapshots it once here, which is what makes
 *   later compiles skip the per-build classpath re-verification.
 * @property outDir daemon-owned work directory: classes, dex, IC caches, aapt2 output.
 * @property aapt2 path to the aapt2 binary provisioned on device. Optional: when
 *   omitted, the daemon discovers it under `$ANDROID_HOME/build-tools/<newest>/aapt2`
 *   (see [org.appdevforall.cotg.quickbuild.daemon.ToolchainDiscovery]) so an external
 *   caller doesn't need to know CoGo's internal toolchain layout.
 * @property d8Jar path to build-tools' r8.jar (carries com.android.tools.r8.D8); loaded
 *   reflectively so the daemon carries no AGP/r8 build dependency. Optional: discovered
 *   the same way as [aapt2] when omitted.
 * @property androidJar the platform android.jar (javac/aapt2 link target). Optional:
 *   discovered under `$ANDROID_HOME/platforms/<newest android-NN>/android.jar` when
 *   omitted.
 * @property minApi min API level for d8; defaults to 30, the quick-build v1 floor
 *   (ResourcesLoader gate, plan 2.4).
 * @property compilerPlugins Kotlin compiler plugin jars passed as `-Xplugin=` to every
 *   compile of the session (e.g. the Compose compiler plugin when the project uses
 *   Compose). Fixed for the session, like the classpath. Optional; defaults to none.
 */
data class ConfigureRequest(
	override val id: Long,
	val projectRoot: String,
	val classpath: List<String>,
	val outDir: String,
	val aapt2: String? = null,
	val d8Jar: String? = null,
	val androidJar: String? = null,
	val minApi: Int = DEFAULT_MIN_API,
	val compilerPlugins: List<String> = emptyList(),
) : DaemonRequest {
	companion object {
		const val DEFAULT_MIN_API = 30
	}
}

/**
 * Incremental compile. [allSources] is the full source set (the IC engine always needs
 * it); [changedFiles] drives `SourcesChanges.Known`. CoGo passes ALL sources as changed
 * on the first build of a session to seed the IC caches (README gotcha).
 *
 * @property removedFiles sources deleted since the last build. Kotlin removals feed
 *   `SourcesChanges.Known`'s removed-files slot (outputs deleted, dependents recompiled);
 *   Java removals have their stale `.class` deleted explicitly. Optional and
 *   backward-compatible: absent/empty is the pre-Bug-12 behavior.
 */
data class CompileRequest(
	override val id: Long,
	val allSources: List<String>,
	val changedFiles: List<String>,
	val removedFiles: List<String> = emptyList(),
) : DaemonRequest

/** Dex the given classes directories into a single classes.dex. */
data class DexRequest(
	override val id: Long,
	val classesDirs: List<String>,
) : DaemonRequest

/**
 * Recompile + relink resources; the response carries the extracted resources.arsc.
 *
 * @property stableIds path to AGP's `stableIds.txt` from the setup build's real resource
 *   processing, if CoGo has one for this project. Passed to `aapt2 link --stable-ids` so
 *   the relink (project res only - a strict subset of what the real build merged in)
 *   pins every resource to the same numeric id the baseline manifest was compiled
 *   against, instead of letting a whole resource TYPE the baseline had (but the relink
 *   doesn't) shift aapt2's type-index assignment out from under that manifest
 *   (ADFA-4128 Bug 6). Optional and backward-compatible: a client that never sends it
 *   gets the pre-fix (unstable) relink behavior, not a protocol error.
 * @property libraryResources pre-compiled `.flat` resource units from the setup build's
 *   real AGP resource processing - the project's own `intermediates/merged_res/` closure
 *   (transitively includes every dependency AAR's VALUES resources) plus each
 *   resource-providing AAR's separately-compiled FILE-based resources. Passed to
 *   `aapt2 link` as `-R` overlays, ordered BEFORE the relink's own fresh compile, so a
 *   library-provided reference (e.g. Material3's `Theme.Material3.DayNight.NoActionBar`)
 *   resolves (ADFA-4128 Bug 8). Optional and backward-compatible: absent/empty on a client
 *   that never reports one gets the pre-fix behavior (project res/ only).
 */
data class RelinkRequest(
	override val id: Long,
	val resDirs: List<String>,
	val manifest: String,
	val stableIds: String? = null,
	val libraryResources: List<String> = emptyList(),
) : DaemonRequest

data class PingRequest(
	override val id: Long,
) : DaemonRequest

data class ShutdownRequest(
	override val id: Long,
) : DaemonRequest

/**
 * One compiler/linker message in the protocol shape. Severity is the closed ERROR |
 * WARNING set from the README; anything a tool reports below warning stays on stderr.
 */
data class Diagnostic(
	val severity: Severity,
	val message: String,
	val file: String? = null,
	val line: Int? = null,
	val column: Int? = null,
) {
	enum class Severity { ERROR, WARNING }
}

/**
 * A single response line. [values] carries the op-specific scalar fields serialized
 * flat into the response object (e.g. `classesDir`, `dexFile`, `resourcesArsc`,
 * `durationMillis`) so the wire shape matches the README's `{"id", "ok", ...}`.
 */
data class DaemonResponse(
	val id: Long,
	val ok: Boolean,
	val values: Map<String, Any> = emptyMap(),
	val diagnostics: List<Diagnostic> = emptyList(),
) {
	companion object {
		/**
		 * Wire-protocol version, stamped into `ping`/`configure` success responses so an
		 * external caller can pin it and abort loudly on drift instead of silently
		 * misinterpreting a changed wire shape.
		 */
		const val PROTOCOL_VERSION = 1

		fun ok(
			id: Long,
			values: Map<String, Any> = emptyMap(),
		): DaemonResponse = DaemonResponse(id, true, values)

		fun failure(
			id: Long,
			diagnostics: List<Diagnostic>,
		): DaemonResponse = DaemonResponse(id, false, emptyMap(), diagnostics)

		fun failure(
			id: Long,
			message: String,
		): DaemonResponse = failure(id, listOf(Diagnostic(Diagnostic.Severity.ERROR, message)))
	}
}

/** Outcome of parsing one stdin line. Malformed input NEVER throws past the codec. */
sealed interface ParseResult {
	data class Parsed(
		val request: DaemonRequest,
	) : ParseResult

	/**
	 * @property id the request id when it could be recovered from the broken input,
	 *   else [UNKNOWN_ID] so the client can still correlate "something failed".
	 */
	data class Malformed(
		val id: Long,
		val message: String,
	) : ParseResult {
		companion object {
			const val UNKNOWN_ID = -1L
		}
	}
}
