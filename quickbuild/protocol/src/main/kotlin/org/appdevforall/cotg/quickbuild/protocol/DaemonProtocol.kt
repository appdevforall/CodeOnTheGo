package org.appdevforall.cotg.quickbuild.protocol

/**
 * A request the daemon accepts: one line-delimited JSON object over stdin, answered by one
 * [DaemonResponse] line over stdout.
 *
 * These types live in :quickbuild:protocol so the daemon and CoGo's client compile against one
 * definition of the wire rather than two conventions pinned by prose.
 *
 * @property id caller-assigned request id, echoed back on the matching [DaemonResponse] so a
 *   client can correlate answers; uniqueness is a convention the daemon does not enforce.
 */
sealed interface DaemonRequest {
	val id: Long
}

/**
 * Opens a session and fixes everything that stays constant for it. Every path arrives absolute.
 *
 * @property id request id, echoed on the response; see [DaemonRequest.id].
 * @property projectRoot the user project root; identifies the session and is never written to.
 * @property classpath compile classpath jars, snapshotted once here so later compiles skip
 *   per-build classpath re-verification.
 * @property outDir daemon-owned work directory: classes, dex, IC caches, aapt2 output.
 * @property aapt2 the aapt2 binary on device; required, as the daemon never guesses a tool path.
 * @property d8Jar build-tools' r8.jar, loaded reflectively so the daemon needs no AGP/r8 build
 *   dependency; required, like [aapt2].
 * @property androidJar the platform android.jar; required, like [aapt2].
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
		/** Default [minApi]: the lowest API level quick build supports. */
		const val DEFAULT_MIN_API = 30
	}
}

/**
 * Compiles the project incrementally.
 *
 * @property id request id, echoed on the response; see [DaemonRequest.id].
 * @property allSources the full source set, which the IC engine needs on every build.
 * @property changedFiles sources edited since the last build, driving `SourcesChanges.Known`;
 *   CoGo repeats all of [allSources] here on a session's first build to seed the IC caches.
 * @property removedFiles sources deleted since the last build; Kotlin removals feed
 *   `SourcesChanges.Known`, Java removals have their stale `.class` deleted explicitly.
 */
data class CompileRequest(
	override val id: Long,
	val allSources: List<String>,
	val changedFiles: List<String>,
	val removedFiles: List<String> = emptyList(),
) : DaemonRequest

/**
 * Dexes the given classes directories into a single classes.dex.
 *
 * @property id request id, echoed on the response; see [DaemonRequest.id].
 * @property classesDirs absolute class-tree roots to dex, merged into one output in the order
 *   given; every `.class` under each is re-dexed, changed or not.
 */
data class DexRequest(
	override val id: Long,
	val classesDirs: List<String>,
) : DaemonRequest

/**
 * Recompiles and relinks resources; the response carries the extracted resources.arsc.
 *
 * @property id request id, echoed on the response; see [DaemonRequest.id].
 * @property resDirs the project's own `res/` roots to recompile, absolute. Library resources are
 *   not walked here - they arrive pre-compiled via [libraryResources].
 * @property manifest absolute path to the merged AndroidManifest.xml the relink links against.
 * @property stableIds AGP's `stableIds.txt` from the proxy app build, passed to `aapt2 link
 *   --stable-ids`; optional, but omitting it lets aapt2's unpinned type ids shift out from under
 *   the baseline manifest (see Aapt2Link).
 * @property libraryResources pre-compiled `.flat` units from the proxy app build, passed as `-R`
 *   overlays so a library-provided reference still resolves; optional, and omitting it relinks
 *   against the project's res/ alone.
 */
data class RelinkRequest(
	override val id: Long,
	val resDirs: List<String>,
	val manifest: String,
	val stableIds: String? = null,
	val libraryResources: List<String> = emptyList(),
) : DaemonRequest

/**
 * Liveness check; the response stamps the protocol version.
 *
 * @property id request id, echoed on the response; see [DaemonRequest.id].
 */
data class PingRequest(
	override val id: Long,
) : DaemonRequest

/**
 * Ends the session and stops the daemon process.
 *
 * @property id request id, echoed on the response; see [DaemonRequest.id].
 */
data class ShutdownRequest(
	override val id: Long,
) : DaemonRequest

/**
 * One compiler or linker message in the protocol shape. Only ERROR and WARNING travel here;
 * anything a tool reports below warning stays on stderr.
 *
 * @property severity ERROR or WARNING; a response carrying any ERROR is a failed op.
 * @property message the tool's text, verbatim and possibly multi-line.
 * @property file absolute path the message points at, or null when the tool reported no location
 *   (a whole-invocation failure such as a bad aapt2 argument).
 * @property line 1-based line in [file], or null when the tool gave none.
 * @property column 1-based column in [line], or null when the tool gave none.
 */
data class Diagnostic(
	val severity: Severity,
	val message: String,
	val file: String? = null,
	val line: Int? = null,
	val column: Int? = null,
) {
	/** How bad a [Diagnostic] is; the only two levels the wire format carries. */
	enum class Severity { ERROR, WARNING }
}

/**
 * The `op` values the daemon dispatches on, one per [DaemonRequest] type.
 *
 * Named here rather than spelled on each side because the client writes the value and the daemon
 * matches it: an unrecognised op is a runtime "unknown op" rejection, never a compile error.
 */
object DaemonOps {
	/** [ConfigureRequest]. */
	const val CONFIGURE = "configure"

	/** [CompileRequest]. */
	const val COMPILE = "compile"

	/** [DexRequest]. */
	const val DEX = "dex"

	/** [RelinkRequest]. */
	const val RELINK = "relink"

	/** [PingRequest]. */
	const val PING = "ping"

	/** [ShutdownRequest]. */
	const val SHUTDOWN = "shutdown"
}

/**
 * The field names of every request line, one constant per property of the [DaemonRequest] types.
 *
 * The wire is untyped JSON, so nothing links the name the client writes to the name the daemon
 * reads: renaming one side alone compiles clean on both and fails only when a real build runs -
 * a required field then reads as missing and the op is rejected as malformed. These constants are
 * the link. Change a name here and both ends move together.
 */
object RequestKeys {
	/** Request id, echoed on the response as [ResponseKeys.ID]; see [DaemonRequest.id]. */
	const val ID = "id"

	/** Which op this line is; one of [DaemonOps]. */
	const val OP = "op"

	/** [ConfigureRequest.projectRoot]. */
	const val PROJECT_ROOT = "projectRoot"

	/** [ConfigureRequest.classpath]. */
	const val CLASSPATH = "classpath"

	/** [ConfigureRequest.outDir]. */
	const val OUT_DIR = "outDir"

	/** [ConfigureRequest.aapt2]. */
	const val AAPT2 = "aapt2"

	/** [ConfigureRequest.d8Jar]. */
	const val D8_JAR = "d8Jar"

	/** [ConfigureRequest.androidJar]. */
	const val ANDROID_JAR = "androidJar"

	/** [ConfigureRequest.minApi]. */
	const val MIN_API = "minApi"

	/** [ConfigureRequest.compilerPlugins]. */
	const val COMPILER_PLUGINS = "compilerPlugins"

	/** [CompileRequest.allSources]. */
	const val ALL_SOURCES = "allSources"

	/** [CompileRequest.changedFiles]. */
	const val CHANGED_FILES = "changedFiles"

	/** [CompileRequest.removedFiles]. */
	const val REMOVED_FILES = "removedFiles"

	/** [DexRequest.classesDirs]. */
	const val CLASSES_DIRS = "classesDirs"

	/** [RelinkRequest.resDirs]. */
	const val RES_DIRS = "resDirs"

	/** [RelinkRequest.manifest]. */
	const val MANIFEST = "manifest"

	/** [RelinkRequest.stableIds]. */
	const val STABLE_IDS = "stableIds"

	/** [RelinkRequest.libraryResources]. */
	const val LIBRARY_RESOURCES = "libraryResources"
}

/**
 * Response field names that do not belong to a stats group: the envelope, and the op-specific
 * scalars of [DaemonResponse.values].
 *
 * Same reason as [RequestKeys]: the daemon writes these and the client reads them with no type
 * between the two. A renamed output-path key such as [CLASSES_DIR] simply reads back absent, which
 * the client's mandatory-key rule turns into a failed build rather than a stale deploy.
 */
object ResponseKeys {
	/** The [DaemonRequest.id] being answered - the same field the request carried. */
	const val ID = RequestKeys.ID

	/** [DaemonResponse.ok]. */
	const val OK = "ok"

	/** [DaemonResponse.diagnostics]; absent rather than empty when there are none. */
	const val DIAGNOSTICS = "diagnostics"

	/** Stamped into `ping`/`configure` success; see [DaemonResponse.PROTOCOL_VERSION]. */
	const val PROTOCOL_VERSION = "protocolVersion"

	/**
	 * Filesystem type of the daemon's work directory as `configure` observed it (`ext4`, `f2fs`,
	 * `fuse`, ...). Reported because it is the strongest predictor of on-device build time - a
	 * class tree copies ~50x slower on FUSE-backed emulated storage than on the app's own
	 * filesystem `[measured on a56]` - so a timing row cannot be interpreted without it.
	 */
	const val SCRATCH_FS_TYPE = "scratchFsType"

	/** How long the op took inside the daemon; reported by every op. */
	const val DURATION_MILLIS = "durationMillis"

	/** `compile`: the class-output tree root. */
	const val CLASSES_DIR = "classesDir"

	/** `compile`: relative `.class` paths this run emitted, for the deploy policy to intersect. */
	const val CLASSES_CHANGED = "classesChanged"

	/** `compile`: the Kotlin half. */
	const val KOTLIN_MILLIS = "kotlinMillis"

	/** `compile`: the Java half. */
	const val JAVA_MILLIS = "javaMillis"

	/** `dex`: the produced classes.dex. */
	const val DEX_FILE = "dexFile"

	/** `dex`: stripping the class tree down to what d8 is fed. */
	const val STRIP_MILLIS = "stripMillis"

	/** `dex`: dexing that stripped tree. */
	const val D8_MILLIS = "d8Millis"

	/**
	 * `relink`: the relinked resource apk. The name says `arsc` for protocol stability, but the
	 * payload is the full apk (resources.arsc plus every compiled resource file) - see Aapt2Link.
	 */
	const val RESOURCES_ARSC = "resourcesArsc"

	/** `relink`: compiling the changed resources. */
	const val AAPT2_COMPILE_MILLIS = "aapt2CompileMillis"

	/** `relink`: relinking the resource table. */
	const val AAPT2_LINK_MILLIS = "aapt2LinkMillis"

	/** Field names inside one entry of the [DIAGNOSTICS] array; one per [Diagnostic] property. */
	object Diagnostics {
		/** [Diagnostic.severity], as the enum's `name`. */
		const val SEVERITY = "severity"

		/** [Diagnostic.message]. */
		const val MESSAGE = "message"

		/** [Diagnostic.file]; omitted when the tool reported no location. */
		const val FILE = "file"

		/** [Diagnostic.line]; omitted when the tool gave none. */
		const val LINE = "line"

		/** [Diagnostic.column]; omitted when the tool gave none. */
		const val COLUMN = "column"
	}
}

/**
 * The phase counters a `compile` op measures beyond `kotlinMillis` / `javaMillis`.
 *
 * Those two cover only about half of a warm edit; the rest is the output-tree snapshots, the
 * Java-ABI re-parse and the source I/O around them - so without these fields javac reads like the
 * bottleneck, when it is 19-27% of a warm edit `[measured on a56]`. Every field is a counter or a
 * duration, never derived from a path, a name or source content.
 *
 * @property preSnapMillis walking the output tree before the compile, to diff against.
 * @property postSnapMillis the same walk after, which yields the changed-class set.
 * @property javaAbiSnapMillis re-parsing every `.java` source's declarations to decide whether a
 *   Java ABI moved, which forces a full Kotlin recompile.
 * @property allSources size of the source set handed to the compiler.
 * @property kotlinToCompile Kotlin sources the daemon DECLARED changed to the Kotlin engine,
 *   which is not the same as the number recompiled: the engine widens that set from its own
 *   dependency graph and can recompile files we declared nothing about. Read it as the size of
 *   the dirty set we handed over - an ABI-changing Java edit hands over all of them.
 * @property javaSources `.java` sources, all of which javac recompiles every build.
 * @property changedClasses `.class` files this build emitted or rewrote.
 * @property compileOrdinal 1-based index of this compile within the session, where `1` is the cold
 *   build that seeds the caches and pays kotlinc's warm-up, so reading it as a warm edit badly
 *   overstates per-edit cost.
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
	/**
	 * Flattens the stats into response values, keyed by the constants below.
	 *
	 * @return every field, one entry per `KEY_*` constant, ready to merge into
	 *   [DaemonResponse.values]; zero-valued fields are emitted too, so telling an unmeasured field
	 *   from a measured zero is [fromValues]'s job.
	 */
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
		 *
		 * @param lookup reads one response value by key as a Long, returning null when the key is
		 *   absent; the caller owns the numeric coercion from whatever the JSON carried.
		 * @return the stats, or null if the response carries none of the `KEY_*` keys.
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
	/**
	 * Flattens the stats into response values, keyed by the constants below.
	 *
	 * @return both fields, keyed by [KEY_CLASS_FILES] and [KEY_CLASS_BYTES], ready to merge into
	 *   [DaemonResponse.values].
	 */
	fun toValues(): Map<String, Any> =
		mapOf(
			KEY_CLASS_FILES to classFiles,
			KEY_CLASS_BYTES to classBytes,
		)

	companion object {
		const val KEY_CLASS_FILES = "nClassFiles"
		const val KEY_CLASS_BYTES = "classBytes"

		/**
		 * Same absent-vs-zero convention as [CompileStats.fromValues].
		 *
		 * @param lookup reads one response value by key as a Long, returning null when absent.
		 * @return the stats, or null if the response carries neither key.
		 */
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
 * Adding a response key must not bump [PROTOCOL_VERSION]: the version is a hard gate that aborts
 * the session on mismatch, and a staged daemon jar can lag the client, so bumping it for an
 * additive field would break a pairing that would otherwise work.
 *
 * @property id the [DaemonRequest.id] this answers; the client's only correlation handle.
 * @property ok whether the op succeeded, false implying at least one ERROR in [diagnostics].
 * @property values op-specific scalars, flat and JSON-scalar-only, keyed by the `KEY_*` constants
 *   and [ResponseKeys], so a client may read one key and ignore the rest.
 * @property diagnostics compiler and linker messages, present on success too since a build can
 *   succeed with warnings.
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

		/**
		 * Builds a success response, with no diagnostics.
		 *
		 * @param id the [DaemonRequest.id] being answered.
		 * @param values op-specific scalars to return; see [DaemonResponse.values].
		 * @return an `ok = true` response carrying [values] and an empty diagnostic list.
		 */
		fun ok(
			id: Long,
			values: Map<String, Any> = emptyMap(),
		): DaemonResponse = DaemonResponse(id, true, values)

		/**
		 * Builds a failure response from already-parsed tool messages.
		 *
		 * @param id the [DaemonRequest.id] being answered.
		 * @param diagnostics the messages to report; the caller is expected to include at least
		 *   one ERROR, since `ok = false` with warnings alone would not explain the failure.
		 * @return an `ok = false` response with no [values].
		 */
		fun failure(
			id: Long,
			diagnostics: List<Diagnostic>,
		): DaemonResponse = DaemonResponse(id, false, emptyMap(), diagnostics)

		/**
		 * Builds a failure response for a whole-op error with no source location.
		 *
		 * @param id the [DaemonRequest.id] being answered.
		 * @param message the error text, wrapped as a single locationless ERROR [Diagnostic].
		 * @return an `ok = false` response carrying that one diagnostic.
		 */
		fun failure(
			id: Long,
			message: String,
		): DaemonResponse = failure(id, listOf(Diagnostic(Diagnostic.Severity.ERROR, message)))
	}
}

/** Outcome of parsing one stdin line. Malformed input never throws past the codec. */
sealed interface ParseResult {
	/**
	 * A line that decoded into a request the daemon can dispatch.
	 *
	 * @property request the decoded request; its `id` is the one to answer on.
	 */
	data class Parsed(
		val request: DaemonRequest,
	) : ParseResult

	/**
	 * A line the codec could not turn into a request.
	 *
	 * @property id the request id when it could be recovered from the broken input, else
	 *   [UNKNOWN_ID] so the client can still correlate "something failed".
	 * @property message why the line was rejected, reported back as the failure response's one
	 *   ERROR diagnostic.
	 */
	data class Malformed(
		val id: Long,
		val message: String,
	) : ParseResult {
		companion object {
			/** [id] when the broken line yielded no usable request id. No real request uses it. */
			const val UNKNOWN_ID = -1L
		}
	}
}
