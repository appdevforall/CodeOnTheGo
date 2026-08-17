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
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.protocol.DaemonOps
import org.appdevforall.cotg.quickbuild.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.protocol.DexStats
import org.appdevforall.cotg.quickbuild.protocol.RequestKeys
import org.appdevforall.cotg.quickbuild.protocol.ResponseKeys
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the quick-build daemon as a child JVM and speaks its line-delimited JSON protocol.
 *
 * Spawns the staged daemon jar on the bundled JDK and talks over stdin/stdout, all process I/O
 * on [Dispatchers.IO] with one request in flight at a time ([requestMutex]) as the protocol
 * requires. A watcher coroutine waits on the process: an exit without a preceding [shutdown]
 * fails every pending request and fires the death listener, which the session manager turns
 * into the Degraded/respawn flow.
 *
 * @property paths staged on-device locations - the JDK binary to spawn, the daemon jar (whose
 *   parent becomes the child's cwd), and the child's environment.
 * @property scope coroutine scope the stdout pump, stderr drain, and death watcher run in;
 *   cancelling it abandons those readers but does not kill the child, which [shutdown] does.
 * @property requestTimeoutMillis per-request ceiling in milliseconds, past which the call yields
 *   a [DaemonReply.Failed] rather than an exception and releases the request slot.
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

	/**
	 * Deliberate-stop marker of the child [process] currently holds, replaced on every spawn
	 * rather than shared between them: a replaced child's watcher passes its identity guard and
	 * only then reads this, so a shared flag the next [start] had already cleared would report a
	 * death for a daemon that was deliberately replaced.
	 */
	@Volatile private var deliberateStop = AtomicBoolean(false)

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
	 *   only when no [shutdown] stopped that particular child - a later child's shutdown or
	 *   start never suppresses it, and never causes it; null clears it.
	 */
	override fun setDeathListener(listener: ((Int) -> Unit)?) {
		deathListener = listener
	}

	/**
	 * Shuts down any running daemon, spawns a fresh child JVM, and sends `configure`.
	 *
	 * @param config the session-fixed settings sent in the `configure` request.
	 * @return [DaemonReply.Ok] once configure succeeded and the protocol version matched, else
	 *   [DaemonReply.Failed] (spawn failure, protocol mismatch, or a rejected configuration) with
	 *   the child shut down first, so a failed start never leaves a daemon behind.
	 */
	override suspend fun start(config: DaemonConfig): DaemonReply<Unit> {
		shutdown()
		// A fresh marker instead of clearing the old one: the child shutdown() just stopped
		// keeps - and its watcher still reads - the instance it was marked on.
		val stopFlag = AtomicBoolean(false)
		this.deliberateStop = stopFlag
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
		startReaders(proc, stopFlag)

		val configureReply =
			request(DaemonOps.CONFIGURE) {
				addProperty(RequestKeys.PROJECT_ROOT, config.projectRoot.absolutePath)
				add(RequestKeys.CLASSPATH, config.classpath.toJsonPaths())
				addProperty(RequestKeys.OUT_DIR, config.outDir.absolutePath)
				addProperty(RequestKeys.AAPT2, config.aapt2.absolutePath)
				addProperty(RequestKeys.D8_JAR, config.d8Jar.absolutePath)
				addProperty(RequestKeys.ANDROID_JAR, config.androidJar.absolutePath)
				if (config.compilerPlugins.isNotEmpty()) {
					add(RequestKeys.COMPILER_PLUGINS, config.compilerPlugins.toJsonPaths())
				}
			}
		val outcome =
			when (configureReply) {
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
		// A start that never reached a configured daemon must not leave the child behind: nothing
		// else shuts it down, so it would hold its heap for the rest of the app's life and fire
		// deathListener for a session that never had a daemon.
		if (outcome !is DaemonReply.Ok) {
			shutdown()
		}
		return outcome
	}

	/**
	 * Sends one `compile` request and unpacks its classes dir, changed-class list, and timings.
	 *
	 * @param allSources every source file of the module, so the daemon can seed or re-seed its
	 *   incremental caches.
	 * @param changedFiles the sources to treat as dirty this round.
	 * @param removedFiles sources deleted since the last build; omitted from the wire when
	 *   empty, which keeps a daemon predating the field working.
	 * @return the compile output, or the daemon's diagnostics / transport failure unchanged, with
	 *   [CompileOutput.changedClassFiles] null when the daemon omitted the signal.
	 */
	override suspend fun compile(
		allSources: List<File>,
		changedFiles: List<File>,
		removedFiles: List<File>,
	): DaemonReply<CompileOutput> {
		val reply =
			request(DaemonOps.COMPILE) {
				add(RequestKeys.ALL_SOURCES, allSources.toJsonPaths())
				add(RequestKeys.CHANGED_FILES, changedFiles.toJsonPaths())
				if (removedFiles.isNotEmpty()) {
					add(RequestKeys.REMOVED_FILES, removedFiles.toJsonPaths())
				}
			}
		val response = (reply as? DaemonReply.Ok)?.value
		// Absent field (a daemon predating the signal) stays null - "unknown", which the
		// deploy policy treats conservatively - distinct from an empty list ("nothing").
		val changed =
			(response?.get(ResponseKeys.CLASSES_CHANGED) as? JsonArray)
				?.mapNotNull { it.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString }
		return reply.mapFile(ResponseKeys.CLASSES_DIR).mapOk {
			CompileOutput(
				it,
				changed,
				kotlinMillis = response.longOrNull(ResponseKeys.KOTLIN_MILLIS),
				javaMillis = response.longOrNull(ResponseKeys.JAVA_MILLIS),
				stats = CompileStats.fromValues { key -> response.longOrNull(key) },
			)
		}
	}

	/**
	 * Sends one `dex` request and unpacks the produced dex plus the pass's timings.
	 *
	 * @param classesDirs class-output directories to dex together, in the order the daemon
	 *   should read them.
	 * @return the dex output, or the daemon's diagnostics / transport failure unchanged; a reply
	 *   that omits `dexFile` is a [DaemonReply.Failed], never a guessed path.
	 */
	override suspend fun dex(classesDirs: List<File>): DaemonReply<DexOutput> {
		val reply =
			request(DaemonOps.DEX) {
				add(RequestKeys.CLASSES_DIRS, classesDirs.toJsonPaths())
			}
		val response = (reply as? DaemonReply.Ok)?.value
		return reply.mapFile(ResponseKeys.DEX_FILE).mapOk {
			DexOutput(
				it,
				stripMillis = response.longOrNull(ResponseKeys.STRIP_MILLIS),
				d8Millis = response.longOrNull(ResponseKeys.D8_MILLIS),
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
			request(DaemonOps.RELINK) {
				add(RequestKeys.RES_DIRS, inputs.resDirs.toJsonPaths())
				addProperty(RequestKeys.MANIFEST, inputs.manifest.absolutePath)
				inputs.stableIdsFile?.let { addProperty(RequestKeys.STABLE_IDS, it.absolutePath) }
				if (inputs.libraryResources.isNotEmpty()) {
					add(RequestKeys.LIBRARY_RESOURCES, inputs.libraryResources.toJsonPaths())
				}
			}
		val response = (reply as? DaemonReply.Ok)?.value
		return reply.mapFile(ResponseKeys.RESOURCES_ARSC).mapOk {
			RelinkOutput(
				it,
				aapt2CompileMillis = response.longOrNull(ResponseKeys.AAPT2_COMPILE_MILLIS),
				aapt2LinkMillis = response.longOrNull(ResponseKeys.AAPT2_LINK_MILLIS),
			)
		}
	}

	/** @return true when the daemon answered `ping` inside [requestTimeoutMillis]. */
	override suspend fun ping(): Boolean = request(DaemonOps.PING) {} is DaemonReply.Ok

	/**
	 * Stops the child politely, then forcibly, and clears the process handles. A no-op when
	 * nothing is running; the exit it causes is marked deliberate so no death listener fires.
	 */
	override suspend fun shutdown() {
		val proc = process ?: return
		// Marked before anything can kill it, so every exit from here on is deliberate to the
		// watcher no matter how late it observes it.
		deliberateStop.set(true)
		configured = false
		// Best effort polite stop; the protocol also treats stdin EOF as shutdown.
		withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) { request(DaemonOps.SHUTDOWN) {} }
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
	 * @return the raw response object on success; holds [requestMutex] for the whole round-trip,
	 *   so callers serialize automatically.
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
					addProperty(RequestKeys.ID, id)
					addProperty(RequestKeys.OP, op)
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

			// Primitive-guarded like every other read: asBoolean on an object or array throws,
			// and this facade promises never to throw for a build problem.
			if (response.get(ResponseKeys.OK)?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
				DaemonReply.Ok(response)
			} else {
				DaemonReply.BuildFailed(parseDiagnostics(response))
			}
		}

	/**
	 * Launches the stdout response pump, the stderr log drain, and the process-death watcher.
	 *
	 * @param proc the freshly spawned child; all three coroutines live on [scope] and end when its
	 *   streams close, so they need no separate cancellation.
	 * @param stopFlag [proc]'s own [deliberateStop] marker, closed over by the watcher so a later
	 *   spawn's marker can never answer "was this exit deliberate?" for this child.
	 */
	private fun startReaders(
		proc: Process,
		stopFlag: AtomicBoolean,
	) {
		scope.launch(Dispatchers.IO) {
			try {
				proc.inputStream.bufferedReader().forEachLine { line ->
					val json =
						runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
					// The id read needs the same guard as the parse: a non-numeric or nested
					// id would throw out of forEachLine, killing this pump for the rest of
					// the session. Every later request would then burn its full timeout and
					// still see the process alive, so nothing would ever respawn the daemon.
					val id = json?.get(ResponseKeys.ID)?.runCatching { asLong }?.getOrNull()
					if (id == null) {
						log.debug("daemon: {}", line)
						return@forEachLine
					}
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
			// A child the respawn replaced dies asynchronously - destroyForcibly returns before
			// the exit - so this can wake up after the NEXT child is already spawned. pending and
			// configured below are shared across spawns, so touching them then would fail the new
			// session's configure ("Daemon did not answer 'configure'").
			if (process !== proc) {
				log.debug("Replaced quick-build daemon exited with code {}", exitCode)
				return@launch
			}
			val abandoned = IOException("Daemon process exited (code $exitCode)")
			pending.values.forEach { it.completeExceptionally(abandoned) }
			pending.clear()
			configured = false
			// This child's own marker, not a shared flag - see [deliberateStop].
			if (!stopFlag.get()) {
				log.error("Quick-build daemon died with exit code {}", exitCode)
				deathListener?.invoke(exitCode)
			}
		}
	}

	/**
	 * Reads the `diagnostics` array off a failed response.
	 *
	 * @param response the `ok=false` response object.
	 * @return one [BuildDiagnostic] per well-formed entry, empty when the key is absent or not an
	 *   array; anything but an explicit `WARNING` reads as an error and a missing message becomes
	 *   "unknown error", so a diagnostic is never dropped for being thin.
	 */
	private fun parseDiagnostics(response: JsonObject): List<BuildDiagnostic> {
		val array = response.get(ResponseKeys.DIAGNOSTICS) as? JsonArray ?: return emptyList()
		return array.mapNotNull { element ->
			val obj = element as? JsonObject ?: return@mapNotNull null
			BuildDiagnostic(
				severity =
					if (obj.get(ResponseKeys.Diagnostics.SEVERITY)?.asString.equals("WARNING", ignoreCase = true)) {
						BuildDiagnostic.Severity.WARNING
					} else {
						BuildDiagnostic.Severity.ERROR
					},
				message = obj.get(ResponseKeys.Diagnostics.MESSAGE)?.asString ?: "unknown error",
				file = obj.get(ResponseKeys.Diagnostics.FILE)?.takeIf { it.isJsonPrimitive }?.asString,
				line = obj.get(ResponseKeys.Diagnostics.LINE)?.takeIf { it.isJsonPrimitive }?.asInt,
				column = obj.get(ResponseKeys.Diagnostics.COLUMN)?.takeIf { it.isJsonPrimitive }?.asInt,
			)
		}
	}

	/**
	 * Extracts an output file path from an op response. The key is mandatory: a conventional
	 * fallback under `outDir` resolves whatever the previous build left there, so the client
	 * would dex and deploy stale artifacts and report success with the user's edit missing, and
	 * the protocol does not bump its version for a key rename
	 * ([DaemonResponse.PROTOCOL_VERSION]), so nothing else catches that drift.
	 *
	 * @param field response key holding the path; the daemon has written it on every `ok`
	 *   response for this op since the op existed.
	 * @return the resolved file, the non-Ok reply unchanged, or a fresh [DaemonReply.Failed]
	 *   naming [field] when the key is absent, non-primitive or empty.
	 */
	private fun DaemonReply<JsonObject>.mapFile(field: String): DaemonReply<File> =
		when (this) {
			is DaemonReply.Ok -> {
				val path =
					value
						.get(field)
						?.takeIf { it.isJsonPrimitive }
						?.asString
						?.takeIf { it.isNotEmpty() }
				if (path == null) {
					DaemonReply.Failed("Daemon reply missing '$field'")
				} else {
					DaemonReply.Ok(File(path))
				}
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
