package org.appdevforall.cotg.quickbuild.daemon.protocol

/**
 * The daemon wire protocol model (quick-build/README.md "Daemon protocol"). One
 * line-delimited JSON request at a time over stdin, one JSON response line over stdout.
 * These types are pure data so the codec and router unit-test on the JVM without any
 * process plumbing.
 *
 * Lives in :quickbuild-protocol so the daemon (:quickbuild-daemon) and CoGo's client
 * (:quick-build DaemonProcessClient) compile against one definition of the wire shape
 * instead of two conventions pinned only by the README.
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
 * Well-known response value keys that do not belong to a stats group. Named here so the
 * daemon that writes them and the client that reads them compile against one definition.
 */
object ResponseKeys {
	/** Stamped into `ping`/`configure` success; see [DaemonResponse.PROTOCOL_VERSION]. */
	const val PROTOCOL_VERSION = "protocolVersion"

	/**
	 * Filesystem type of the daemon's work directory, as `configure` observed it (e.g.
	 * `ext4`, `f2fs`, `fuse`). Session-constant and low-cardinality. It is here because
	 * it is the single strongest predictor of build time on device: the same 464-file
	 * class tree copies in 192 ms on the app's own filesystem and 9985 ms on Android's
	 * FUSE-backed emulated storage - 52x (ADFA-4128 sora-editor-full deep-dive). Without
	 * it, a field timing row cannot be interpreted at all.
	 */
	const val SCRATCH_FS_TYPE = "scratchFsType"
}

/**
 * The phase counters a `compile` op measures inside itself - the spans that the
 * `kotlinMillis`/`javaMillis` pair does NOT cover.
 *
 * These exist because those two fields account for only about half of a warm edit: the
 * rest is the output-tree snapshots, the Java-ABI re-parse, and the source I/O around
 * them, all of which are per-file filesystem work and all of which were invisible. A
 * design note read the old fields and concluded javac was "the bottleneck" when javac is
 * 19-27% of a warm edit (ADFA-4128 sora-editor-full deep-dive, section 5).
 *
 * Every field is a counter or a duration. Nothing here is derived from a path, a name, or
 * source content.
 *
 * @property preSnapMillis walking the output tree before the compile, to diff against.
 * @property postSnapMillis the same walk after, which yields the changed-class set.
 * @property javaAbiSnapMillis re-parsing every `.java` source's declarations to decide
 *   whether a Java ABI moved (which forces a full Kotlin recompile).
 * @property allSources size of the source set handed to the compiler.
 * @property kotlinToCompile Kotlin sources this build actually recompiled - the number
 *   that explains a slow row (an ABI-changing Java edit recompiles all of them).
 * @property javaSources `.java` sources, all of which javac recompiles every build.
 * @property changedClasses `.class` files this build emitted or rewrote.
 * @property compileOrdinal 1-based index of this compile within the daemon session. `1`
 *   is the session's cold build - it seeds the incremental caches and pays kotlinc's
 *   warm-up, and reading one as a warm edit is what made a 53 s first build look like a
 *   per-edit cost. Everything above 1 is a warm build.
 */
data class CompileStats(
	val preSnapMillis: Long = 0,
	val postSnapMillis: Long = 0,
	val javaAbiSnapMillis: Long = 0,
	val allSources: Int = 0,
	val kotlinToCompile: Int = 0,
	val javaSources: Int = 0,
	val changedClasses: Int = 0,
	val compileOrdinal: Long = 0,
) {
	fun toValues(): Map<String, Any> =
		mapOf(
			KEY_PRE_SNAP_MILLIS to preSnapMillis,
			KEY_POST_SNAP_MILLIS to postSnapMillis,
			KEY_JAVA_ABI_SNAP_MILLIS to javaAbiSnapMillis,
			KEY_ALL_SOURCES to allSources,
			KEY_KOTLIN_TO_COMPILE to kotlinToCompile,
			KEY_JAVA_SOURCES to javaSources,
			KEY_CHANGED_CLASSES to changedClasses,
			KEY_COMPILE_ORDINAL to compileOrdinal,
		)

	companion object {
		const val KEY_PRE_SNAP_MILLIS = "preSnapMillis"
		const val KEY_POST_SNAP_MILLIS = "postSnapMillis"
		const val KEY_JAVA_ABI_SNAP_MILLIS = "javaAbiSnapMillis"
		const val KEY_ALL_SOURCES = "nAllSources"
		const val KEY_KOTLIN_TO_COMPILE = "nKotlinToCompile"
		const val KEY_JAVA_SOURCES = "nJavaSources"
		const val KEY_CHANGED_CLASSES = "nChangedClasses"
		const val KEY_COMPILE_ORDINAL = "compileOrdinal"

		private val KEYS =
			listOf(
				KEY_PRE_SNAP_MILLIS,
				KEY_POST_SNAP_MILLIS,
				KEY_JAVA_ABI_SNAP_MILLIS,
				KEY_ALL_SOURCES,
				KEY_KOTLIN_TO_COMPILE,
				KEY_JAVA_SOURCES,
				KEY_CHANGED_CLASSES,
				KEY_COMPILE_ORDINAL,
			)

		/**
		 * Reads the stats back out of a response. [lookup] returns null for a key the
		 * response does not carry, so a daemon predating these fields yields null here
		 * rather than a zero-filled row that would read as "measured, and it was free".
		 * An individually missing key defaults to 0 - forward compatibility for a future
		 * daemon that drops one.
		 */
		fun fromValues(lookup: (String) -> Long?): CompileStats? {
			if (KEYS.none { lookup(it) != null }) return null
			return CompileStats(
				preSnapMillis = lookup(KEY_PRE_SNAP_MILLIS) ?: 0,
				postSnapMillis = lookup(KEY_POST_SNAP_MILLIS) ?: 0,
				javaAbiSnapMillis = lookup(KEY_JAVA_ABI_SNAP_MILLIS) ?: 0,
				allSources = lookup(KEY_ALL_SOURCES)?.toInt() ?: 0,
				kotlinToCompile = lookup(KEY_KOTLIN_TO_COMPILE)?.toInt() ?: 0,
				javaSources = lookup(KEY_JAVA_SOURCES)?.toInt() ?: 0,
				changedClasses = lookup(KEY_CHANGED_CLASSES)?.toInt() ?: 0,
				compileOrdinal = lookup(KEY_COMPILE_ORDINAL) ?: 0,
			)
		}
	}
}

/**
 * What a `dex` op processed. The dex step rewrites and re-dexes the WHOLE class tree on
 * every build, changed or not, so these two numbers - not the changed-file count - are
 * what its cost scales with.
 *
 * @property classFiles `.class` files read, stripped and dexed.
 * @property classBytes their total size in bytes.
 */
data class DexStats(
	val classFiles: Int = 0,
	val classBytes: Long = 0,
) {
	fun toValues(): Map<String, Any> =
		mapOf(
			KEY_CLASS_FILES to classFiles,
			KEY_CLASS_BYTES to classBytes,
		)

	companion object {
		const val KEY_CLASS_FILES = "nClassFiles"
		const val KEY_CLASS_BYTES = "classBytes"

		/** Same absent-vs-zero convention as [CompileStats.fromValues]. */
		fun fromValues(lookup: (String) -> Long?): DexStats? {
			val files = lookup(KEY_CLASS_FILES)
			val bytes = lookup(KEY_CLASS_BYTES)
			if (files == null && bytes == null) return null
			return DexStats(classFiles = files?.toInt() ?: 0, classBytes = bytes ?: 0)
		}
	}
}

/**
 * A single response line. [values] carries the op-specific scalar fields serialized
 * flat into the response object (e.g. `classesDir`, `dexFile`, `resourcesArsc`,
 * `durationMillis`) so the wire shape matches the README's `{"id", "ok", ...}`.
 *
 * Extending a response is ADDITIVE and does NOT bump [DaemonResponse.PROTOCOL_VERSION]:
 * a new key is invisible to an older client (it reads the keys it knows), and an absent
 * key reads back as null on a newer client (see [CompileStats.fromValues]). The version
 * is a hard gate - [org.appdevforall.cotg.quickbuild.daemon.protocol.DaemonResponse.PROTOCOL_VERSION]
 * mismatch aborts the session - and a STAGED daemon jar can lag the client, so bumping it
 * for a new optional field would break the very pairing the additive shape supports.
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
