package org.appdevforall.cotg.quickbuild.daemon.protocol

/**
 * A request the daemon accepts: one line-delimited JSON object over stdin, answered by one
 * [DaemonResponse] line over stdout. See quickbuild/core/README.md, "Daemon protocol".
 *
 * These types live in :quickbuild:protocol so the daemon and CoGo's client compile against one
 * definition of the wire shape rather than two conventions pinned only by the README.
 */
sealed interface DaemonRequest {
	val id: Long
}

/**
 * Opens a session and fixes everything that stays constant for it. Every path arrives absolute.
 *
 * @property projectRoot the user project root; identifies the session and is never written to.
 * @property classpath compile classpath jars. Snapshotted once here, which is what lets later
 *   compiles skip per-build classpath re-verification.
 * @property outDir daemon-owned work directory: classes, dex, IC caches, aapt2 output.
 * @property aapt2 the aapt2 binary on device. Optional: discovered under
 *   `$ANDROID_HOME/build-tools/<newest>/` so an external caller need not know CoGo's toolchain
 *   layout.
 * @property d8Jar build-tools' r8.jar, loaded reflectively so the daemon needs no AGP/r8 build
 *   dependency. Optional, discovered like [aapt2].
 * @property androidJar the platform android.jar. Optional, discovered like [aapt2].
 * @property minApi min API level for d8; 30 is the quick-build floor.
 * @property compilerPlugins Kotlin compiler plugin jars passed as `-Xplugin=` to every compile of
 *   the session, e.g. the Compose compiler plugin.
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
 * Compiles the project incrementally.
 *
 * @property allSources the full source set, which the IC engine needs on every build.
 * @property changedFiles sources edited since the last build, driving `SourcesChanges.Known`.
 *   CoGo passes all sources here on a session's first build to seed the IC caches.
 * @property removedFiles sources deleted since the last build. Kotlin removals feed
 *   `SourcesChanges.Known`; Java removals have their stale `.class` deleted explicitly.
 */
data class CompileRequest(
	override val id: Long,
	val allSources: List<String>,
	val changedFiles: List<String>,
	val removedFiles: List<String> = emptyList(),
) : DaemonRequest

/** Dexes the given classes directories into a single classes.dex. */
data class DexRequest(
	override val id: Long,
	val classesDirs: List<String>,
) : DaemonRequest

/**
 * Recompiles and relinks resources; the response carries the extracted resources.arsc.
 *
 * @property stableIds AGP's `stableIds.txt` from the proxy app build, passed to `aapt2 link
 *   --stable-ids`. The relink sees only the project's res/, a subset of what the real build
 *   merged, so without this a resource type missing from the relink shifts aapt2's type-index
 *   assignment out from under the baseline manifest. Optional: omitting it relinks with aapt2's
 *   own unpinned ids.
 * @property libraryResources pre-compiled `.flat` units from the proxy app build, passed to
 *   `aapt2 link` as `-R` overlays ahead of the relink's own compile, so a library-provided
 *   reference still resolves. Optional: omitting it relinks against the project's res/ alone.
 */
data class RelinkRequest(
	override val id: Long,
	val resDirs: List<String>,
	val manifest: String,
	val stableIds: String? = null,
	val libraryResources: List<String> = emptyList(),
) : DaemonRequest

/** Liveness check; the response stamps the protocol version. */
data class PingRequest(
	override val id: Long,
) : DaemonRequest

/** Ends the session and stops the daemon process. */
data class ShutdownRequest(
	override val id: Long,
) : DaemonRequest

/**
 * One compiler or linker message in the protocol shape. Only ERROR and WARNING travel here;
 * anything a tool reports below warning stays on stderr.
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
	 * Filesystem type of the daemon's work directory as `configure` observed it (`ext4`, `f2fs`,
	 * `fuse`, ...). Reported because it is the strongest predictor of on-device build time - a
	 * class tree copies ~50x slower on FUSE-backed emulated storage than on the app's own
	 * filesystem `[measured on a56]` - so a timing row cannot be interpreted without it.
	 */
	const val SCRATCH_FS_TYPE = "scratchFsType"
}

/**
 * The phase counters a `compile` op measures inside itself: the spans the `kotlinMillis` /
 * `javaMillis` pair does not cover.
 *
 * Those two account for only about half of a warm edit; the rest is the output-tree snapshots,
 * the Java-ABI re-parse and the source I/O around them, per-file filesystem work with no span of
 * its own. Without these fields javac reads like the bottleneck, when javac is 19-27% of a warm
 * edit `[measured on a56]`. Every field is a counter or a duration - nothing is derived from a
 * path, a name or source content.
 *
 * @property preSnapMillis walking the output tree before the compile, to diff against.
 * @property postSnapMillis the same walk after, which yields the changed-class set.
 * @property javaAbiSnapMillis re-parsing every `.java` source's declarations to decide whether a
 *   Java ABI moved, which forces a full Kotlin recompile.
 * @property allSources size of the source set handed to the compiler.
 * @property kotlinToCompile Kotlin sources actually recompiled - the number that explains a slow
 *   row, since an ABI-changing Java edit recompiles all of them.
 * @property javaSources `.java` sources, all of which javac recompiles every build.
 * @property changedClasses `.class` files this build emitted or rewrote.
 * @property compileOrdinal 1-based index of this compile within the session. `1` is the cold
 *   build: it seeds the incremental caches and pays kotlinc's warm-up, so reading it as a warm
 *   edit badly overstates per-edit cost.
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
	/** Flattens the stats into response values, keyed by the constants below. */
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
		 * Reads the stats back out of a response, or null if it carries none of them.
		 *
		 * The null return matters: a daemon predating these fields must not yield a zero-filled
		 * row, which would read as "measured, and it was free". A single missing key does
		 * default to 0, so a future daemon may drop one.
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
 * What a `dex` op processed. The step rewrites and re-dexes the whole class tree every build,
 * changed or not, so its cost scales with these two numbers rather than the changed-file count.
 *
 * @property classFiles `.class` files read, stripped and dexed.
 * @property classBytes their total size in bytes.
 */
data class DexStats(
	val classFiles: Int = 0,
	val classBytes: Long = 0,
) {
	/** Flattens the stats into response values, keyed by the constants below. */
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
 * The daemon's answer to one request. [values] holds the op-specific scalars (`classesDir`,
 * `dexFile`, `resourcesArsc`, `durationMillis`, ...), serialized flat into the response object.
 *
 * Adding a response key must not bump [PROTOCOL_VERSION]: a new key is invisible to an older
 * client, and an absent key reads back as null on a newer one. The version is a hard gate that
 * aborts the session on mismatch, and a staged daemon jar can lag the client, so bumping it for
 * an optional field would break the pairing the additive shape exists to support.
 */
data class DaemonResponse(
	val id: Long,
	val ok: Boolean,
	val values: Map<String, Any> = emptyMap(),
	val diagnostics: List<Diagnostic> = emptyList(),
) {
	companion object {
		/**
		 * Wire-protocol version, stamped into `ping` and `configure` success responses so a
		 * caller can pin it and abort loudly on drift rather than misread a changed wire shape.
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

/** Outcome of parsing one stdin line. Malformed input never throws past the codec. */
sealed interface ParseResult {
	data class Parsed(
		val request: DaemonRequest,
	) : ParseResult

	/**
	 * A line the codec could not turn into a request.
	 *
	 * @property id the request id when it could be recovered from the broken input, else
	 *   [UNKNOWN_ID] so the client can still correlate "something failed".
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
