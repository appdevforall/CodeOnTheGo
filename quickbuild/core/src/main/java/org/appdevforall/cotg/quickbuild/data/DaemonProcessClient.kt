package org.appdevforall.cotg.quickbuild.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.daemon.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexStats
import org.appdevforall.cotg.quickbuild.daemon.protocol.ResponseKeys
import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the quick-build daemon as a child JVM and speaks its line-delimited JSON protocol.
 *
 * Spawns the staged daemon jar on the bundled JDK, talks over stdin/stdout, drains stderr to
 * the log. All process I/O runs on [Dispatchers.IO], one request in flight at a time
 * ([requestMutex]) as the protocol requires. A watcher coroutine waits on the process: an
 * exit without a preceding [shutdown] fails every pending request and fires the death
 * listener, which the session manager turns into the Degraded/respawn flow.
 *
 * @property paths staged on-device locations - the JDK binary to spawn, the daemon jar (whose
 *   parent becomes the child's cwd), and the child's environment.
 * @property scope coroutine scope the stdout pump, stderr drain, and death watcher run in.
 *   Cancelling it abandons those readers but does not kill the child; call [shutdown] for that.
 * @property requestTimeoutMillis per-request ceiling in milliseconds. Exceeding it yields a
 *   [DaemonReply.Failed] rather than an exception, and the request slot is released.
 */
class DaemonProcessClient(
	private val paths: QuickBuildPaths,
	private val scope: CoroutineScope,
	private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) : QuickBuildDaemon {
	private val requestMutex = Mutex()
	private val nextId = AtomicLong(1)
	private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

	@Volatile private var process: Process? = null

	@Volatile private var writer: BufferedWriter? = null

	@Volatile private var config: DaemonConfig? = null

	@Volatile private var shutdownRequested = false

	@Volatile private var deathListener: ((Int) -> Unit)? = null

	@Volatile private var configured = false

	@Volatile
	override var scratchFsType: String? = null
		private set

	override val isRunning: Boolean
		get() = configured && process?.isAlive == true

	/**
	 * Installs the unexpected-exit callback, replacing any previous one.
	 *
	 * @param listener called with the child's exit code from the death-watcher coroutine, and
	 *   only when no [shutdown] preceded the exit; null clears it.
	 */
	override fun setDeathListener(listener: ((Int) -> Unit)?) {
		deathListener = listener
	}

	/**
	 * Shuts down any running daemon, spawns a fresh child JVM, and sends `configure`.
	 *
	 * @param config the session-fixed settings sent in the `configure` request; retained so
	 *   [mapFile] can fall back to conventional paths under its `outDir`.
	 * @return [DaemonReply.Ok] once configure succeeded and the daemon's protocol version
	 *   matched, else [DaemonReply.Failed] - spawn failure and protocol mismatch both land
	 *   here, as does a daemon that rejects the configuration.
	 */
	override suspend fun start(config: DaemonConfig): DaemonReply<Unit> {
		shutdown()
		this.config = config
		this.shutdownRequested = false
		// Belongs to the session being replaced; a failed configure must not leave the
		// previous daemon's filesystem stamped on the next session's timings.
		this.scratchFsType = null

		val proc =
			try {
				withContext(Dispatchers.IO) {
					ProcessBuilder(
						listOf(
							paths.javaBinary.absolutePath,
							"-jar",
							paths.daemonJar.absolutePath,
						),
					).run {
						redirectErrorStream(false)
						directory(paths.daemonJar.parentFile)
						// Do not inherit the app env: Android runtime classpath vars can
						// abort a standalone OpenJDK on some OEM images.
						environment().clear()
						environment().putAll(paths.daemonEnvironment())
						start()
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to spawn quick-build daemon", e)
				return DaemonReply.Failed("Failed to spawn daemon: ${e.message}", daemonDied = true)
			}

		process = proc
		writer = proc.outputStream.bufferedWriter()
		startReaders(proc)

		val configureReply =
			request("configure") {
				addProperty("projectRoot", config.projectRoot.absolutePath)
				add("classpath", config.classpath.toJsonPaths())
				addProperty("outDir", config.outDir.absolutePath)
				addProperty("aapt2", config.aapt2.absolutePath)
				addProperty("d8Jar", config.d8Jar.absolutePath)
				addProperty("androidJar", config.androidJar.absolutePath)
				if (config.compilerPlugins.isNotEmpty()) {
					add("compilerPlugins", config.compilerPlugins.toJsonPaths())
				}
			}
		return when (configureReply) {
			is DaemonReply.Ok -> {
				val daemonVersion =
					configureReply.value
						.get(ResponseKeys.PROTOCOL_VERSION)
						?.takeIf { it.isJsonPrimitive }
						?.runCatching { asInt }
						?.getOrNull()
				if (daemonVersion != EXPECTED_PROTOCOL_VERSION) {
					// A missing field fails too: the daemon has stamped it into every
					// configure success since the protocol existed, so absence means
					// "not our daemon".
					DaemonReply.Failed(
						"Daemon protocol version mismatch: daemon reported " +
							"${daemonVersion ?: "no protocolVersion"}, this client expects " +
							"$EXPECTED_PROTOCOL_VERSION",
					)
				} else {
					scratchFsType =
						configureReply.value
							.get(ResponseKeys.SCRATCH_FS_TYPE)
							?.takeIf { it.isJsonPrimitive }
							?.asString
					configured = true
					DaemonReply.Ok(Unit)
				}
			}

			is DaemonReply.BuildFailed -> {
				DaemonReply.Failed("Daemon rejected configuration", daemonDied = false)
			}

			is DaemonReply.Failed -> {
				configureReply
			}
		}
	}

	/**
	 * Sends one `compile` request and unpacks its classes dir, changed-class list, and timings.
	 *
	 * @param allSources every source file of the module, so the daemon can seed or re-seed its
	 *   incremental caches.
	 * @param changedFiles the sources to treat as dirty this round.
	 * @param removedFiles sources deleted since the last build; omitted from the wire when
	 *   empty, which keeps a daemon predating the field working.
	 * @return the compile output, or the daemon's diagnostics / transport failure unchanged.
	 *   [CompileOutput.changedClassFiles] is null when the daemon omitted the signal.
	 */
	override suspend fun compile(
		allSources: List<File>,
		changedFiles: List<File>,
		removedFiles: List<File>,
	): DaemonReply<CompileOutput> {
		val reply =
			request("compile") {
				add("allSources", allSources.toJsonPaths())
				add("changedFiles", changedFiles.toJsonPaths())
				// Omitted when empty: keeps the wire minimal and backward-compatible with a
				// daemon that predates the field (it reads it as an optional list).
				if (removedFiles.isNotEmpty()) {
					add("removedFiles", removedFiles.toJsonPaths())
				}
			}
		val response = (reply as? DaemonReply.Ok)?.value
		// Absent field (a daemon predating the signal) stays null - "unknown", which the
		// deploy policy treats conservatively - distinct from an empty list ("nothing").
		val changed =
			(response?.get("classesChanged") as? JsonArray)
				?.mapNotNull { it.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString }
		return reply.mapFile("classesDir") { File(it, "classes") }.mapOk {
			CompileOutput(
				it,
				changed,
				kotlinMillis = response.longOrNull("kotlinMillis"),
				javaMillis = response.longOrNull("javaMillis"),
				stats = CompileStats.fromValues { key -> response.longOrNull(key) },
			)
		}
	}

	/**
	 * Sends one `dex` request and unpacks the produced dex plus the pass's timings.
	 *
	 * @param classesDirs class-output directories to dex together, in the order the daemon
	 *   should read them.
	 * @return the dex output, or the daemon's diagnostics / transport failure unchanged. The
	 *   dex path falls back to `<outDir>/classes.dex` when the daemon omits the field.
	 */
	override suspend fun dex(classesDirs: List<File>): DaemonReply<DexOutput> {
		val reply =
			request("dex") {
				add("classesDirs", classesDirs.toJsonPaths())
			}
		val response = (reply as? DaemonReply.Ok)?.value
		return reply.mapFile("dexFile") { File(it, "classes.dex") }.mapOk {
			DexOutput(
				it,
				stripMillis = response.longOrNull("stripMillis"),
				d8Millis = response.longOrNull("d8Millis"),
				stats = DexStats.fromValues { key -> response.longOrNull(key) },
			)
		}
	}

	/**
	 * Sends one `relink` request, flattening [inputs] into the protocol's separate keys.
	 *
	 * @param inputs the relink contract; its optional stable-ids and library-resource fields
	 *   are omitted from the wire when absent or empty.
	 * @return the relinked resource apk and aapt2 timings, or the daemon's diagnostics /
	 *   transport failure unchanged.
	 */
	override suspend fun relink(inputs: RelinkInputs): DaemonReply<RelinkOutput> {
		val reply =
			request("relink") {
				add("resDirs", inputs.resDirs.toJsonPaths())
				addProperty("manifest", inputs.manifest.absolutePath)
				inputs.stableIdsFile?.let { addProperty("stableIds", it.absolutePath) }
				if (inputs.libraryResources.isNotEmpty()) {
					add("libraryResources", inputs.libraryResources.toJsonPaths())
				}
			}
		val response = (reply as? DaemonReply.Ok)?.value
		// The wire field is named "resourcesArsc" for protocol stability, but it names the
		// full relinked resource apk (resources.arsc plus every compiled resource file),
		// not a bare table - see Aapt2Link's KDoc. The fallback path matches the daemon's
		// own relink workDir layout.
		return reply.mapFile("resourcesArsc") { File(it, "res/linked-res.apk") }.mapOk {
			RelinkOutput(
				it,
				aapt2CompileMillis = response.longOrNull("aapt2CompileMillis"),
				aapt2LinkMillis = response.longOrNull("aapt2LinkMillis"),
			)
		}
	}

	/** @return true when the daemon answered `ping` inside [requestTimeoutMillis]. */
	override suspend fun ping(): Boolean = request("ping") {} is DaemonReply.Ok

	/**
	 * Stops the child politely, then forcibly, and clears the process handles. A no-op when
	 * nothing is running; the exit it causes is marked deliberate so no death listener fires.
	 */
	override suspend fun shutdown() {
		val proc = process ?: return
		shutdownRequested = true
		configured = false
		// Best effort polite stop; the protocol also treats stdin EOF as shutdown.
		withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) { request("shutdown") {} }
		withContext(Dispatchers.IO) {
			runCatching { writer?.close() }
			if (proc.isAlive && !proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
				proc.destroyForcibly()
			}
		}
		process = null
		writer = null
	}

	/**
	 * Sends one request and awaits the matching-id response. Failure of the transport
	 * (dead process, EOF, timeout) is a [DaemonReply.Failed]; a well-formed
	 * `ok=false` response is a [DaemonReply.BuildFailed] with parsed diagnostics.
	 *
	 * @param op protocol op name, sent as `op` and echoed in timeout messages.
	 * @param fill adds the op's own keys to the request object; `id` and `op` are already set
	 *   and must not be overwritten.
	 * @return the raw response object on success. Holds [requestMutex] for the whole
	 *   round-trip, so callers serialize automatically.
	 */
	private suspend fun request(
		op: String,
		fill: JsonObject.() -> Unit,
	): DaemonReply<JsonObject> =
		requestMutex.withLock {
			val out = writer ?: return DaemonReply.Failed("Daemon is not running", daemonDied = true)
			val id = nextId.getAndIncrement()
			val deferred = CompletableDeferred<JsonObject>()
			pending[id] = deferred

			val requestJson =
				JsonObject().apply {
					addProperty("id", id)
					addProperty("op", op)
					fill()
				}

			try {
				withContext(Dispatchers.IO) {
					out.write(requestJson.toString())
					out.newLine()
					out.flush()
				}
			} catch (e: IOException) {
				pending.remove(id)
				return DaemonReply.Failed("Daemon write failed: ${e.message}", daemonDied = true)
			}

			val response =
				try {
					withTimeoutOrNull(requestTimeoutMillis) { deferred.await() }
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					null
				} finally {
					pending.remove(id)
				}
					?: return DaemonReply.Failed(
						"Daemon did not answer '$op' (dead or timed out)",
						daemonDied = process?.isAlive != true,
					)

			if (response.get("ok")?.asBoolean == true) {
				DaemonReply.Ok(response)
			} else {
				DaemonReply.BuildFailed(parseDiagnostics(response))
			}
		}

	/**
	 * Launches the stdout response pump, the stderr log drain, and the process-death watcher.
	 *
	 * @param proc the freshly spawned child. All three coroutines live on [scope] and end when
	 *   its streams close, so they need no separate cancellation.
	 */
	private fun startReaders(proc: Process) {
		scope.launch(Dispatchers.IO) {
			try {
				proc.inputStream.bufferedReader().forEachLine { line ->
					val json =
						runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
					if (json == null || !json.has("id")) {
						log.debug("daemon: {}", line)
						return@forEachLine
					}
					val id = json.get("id").asLong
					pending.remove(id)?.complete(json)
						?: log.warn("Daemon response for unknown request id {}", id)
				}
			} catch (e: IOException) {
				log.debug("Daemon stdout closed: {}", e.message)
			}
		}
		scope.launch(Dispatchers.IO) {
			try {
				proc.errorStream.bufferedReader().forEachLine { line ->
					log.warn("daemon(stderr): {}", line)
				}
			} catch (e: IOException) {
				// stream closed with the process; nothing to do
			}
		}
		scope.launch(Dispatchers.IO) {
			val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
			val abandoned = IOException("Daemon process exited (code $exitCode)")
			pending.values.forEach { it.completeExceptionally(abandoned) }
			pending.clear()
			configured = false
			if (!shutdownRequested) {
				log.error("Quick-build daemon died with exit code {}", exitCode)
				deathListener?.invoke(exitCode)
			}
		}
	}

	/**
	 * Reads the `diagnostics` array off a failed response.
	 *
	 * @param response the `ok=false` response object.
	 * @return one [BuildDiagnostic] per well-formed entry, empty when the key is absent or not
	 *   an array. Anything but an explicit `WARNING` severity is read as an error, and a
	 *   missing message becomes "unknown error" - a diagnostic is never dropped for being thin.
	 */
	private fun parseDiagnostics(response: JsonObject): List<BuildDiagnostic> {
		val array = response.get("diagnostics") as? JsonArray ?: return emptyList()
		return array.mapNotNull { element ->
			val obj = element as? JsonObject ?: return@mapNotNull null
			BuildDiagnostic(
				severity =
					if (obj.get("severity")?.asString.equals("WARNING", ignoreCase = true)) {
						BuildDiagnostic.Severity.WARNING
					} else {
						BuildDiagnostic.Severity.ERROR
					},
				message = obj.get("message")?.asString ?: "unknown error",
				file = obj.get("file")?.takeIf { it.isJsonPrimitive }?.asString,
				line = obj.get("line")?.takeIf { it.isJsonPrimitive }?.asInt,
				column = obj.get("column")?.takeIf { it.isJsonPrimitive }?.asInt,
			)
		}
	}

	/**
	 * Extracts an output file path from an op response, falling back to a conventional
	 * location under the configured outDir when the daemon omits the field, so a field-name
	 * mismatch is not a hard failure.
	 *
	 * @param field response key holding the path.
	 * @param fallback derives the conventional path from the configured `outDir` when [field]
	 *   is absent or not a primitive.
	 * @return the resolved file, the non-Ok reply unchanged, or a fresh [DaemonReply.Failed]
	 *   when neither the field nor a configured outDir is available.
	 */
	private fun DaemonReply<JsonObject>.mapFile(
		field: String,
		fallback: (File) -> File,
	): DaemonReply<File> =
		when (this) {
			is DaemonReply.Ok -> {
				val path = value.get(field)?.takeIf { it.isJsonPrimitive }?.asString
				val file =
					path?.let(::File)
						?: config?.outDir?.let(fallback)
						?: return DaemonReply.Failed("Daemon reply missing '$field' and no outDir configured")
				DaemonReply.Ok(file)
			}

			is DaemonReply.BuildFailed -> {
				this
			}

			is DaemonReply.Failed -> {
				this
			}
		}

	/**
	 * Optional numeric field: null when absent or non-primitive (a pre-timing daemon).
	 *
	 * @param field response key to read.
	 * @return the value as a Long, or null - including when the receiver itself is null, so a
	 *   non-Ok reply needs no separate guard.
	 */
	private fun JsonObject?.longOrNull(field: String): Long? =
		this
			?.get(field)
			?.takeIf { it.isJsonPrimitive }
			?.runCatching { asLong }
			?.getOrNull()

	/**
	 * Rewraps a success value, leaving both failure arms alone.
	 *
	 * @param transform applied only to a [DaemonReply.Ok] value; must not throw, since nothing
	 *   here converts an exception into a reply.
	 * @return the transformed Ok, or this same failure reply.
	 */
	private fun <T, R> DaemonReply<T>.mapOk(transform: (T) -> R): DaemonReply<R> =
		when (this) {
			is DaemonReply.Ok -> DaemonReply.Ok(transform(value))
			is DaemonReply.BuildFailed -> this
			is DaemonReply.Failed -> this
		}

	/** @return a JSON array of absolute paths, order preserved - the wire form for file lists. */
	private fun List<File>.toJsonPaths(): JsonArray = JsonArray().also { array -> forEach { array.add(it.absolutePath) } }

	companion object {
		private val log = LoggerFactory.getLogger("QB-DaemonClient")

		/**
		 * The wire-protocol version this client speaks, shared with the daemon via
		 * [DaemonResponse.PROTOCOL_VERSION]. [start] rejects a configure reply whose version
		 * differs or is absent, so drift fails at session start rather than as misparsed
		 * replies mid-build - a staged daemon jar older than this client is exactly that case.
		 */
		const val EXPECTED_PROTOCOL_VERSION = DaemonResponse.PROTOCOL_VERSION

		/** Compile of a large changeset can be slow on low-spec; be generous. */
		const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 300_000L

		private const val SHUTDOWN_TIMEOUT_MILLIS = 3_000L
	}
}
