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
import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Child-JVM implementation of [QuickBuildDaemon]: spawns the staged daemon jar on the
 * bundled JDK and speaks the line-delimited JSON protocol from quick-build/README.md
 * over stdin/stdout (stderr is drained to the log).
 *
 * Threading: never blocks the caller's thread - all process I/O runs on
 * [Dispatchers.IO]. One request in flight at a time ([requestMutex]); the daemon
 * protocol requires it and the orchestrator already guarantees it upstream.
 *
 * Death detection: a watcher coroutine waits on the process; an exit without a
 * preceding [shutdown] fails every pending request and fires the death listener, which
 * the session manager turns into the Degraded/respawn flow.
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

	override val isRunning: Boolean
		get() = configured && process?.isAlive == true

	override fun setDeathListener(listener: ((Int) -> Unit)?) {
		deathListener = listener
	}

	override suspend fun start(config: DaemonConfig): DaemonReply<Unit> {
		shutdown()
		this.config = config
		this.shutdownRequested = false

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
				configured = true
				DaemonReply.Ok(Unit)
			}
			is DaemonReply.BuildFailed ->
				DaemonReply.Failed("Daemon rejected configuration", daemonDied = false)
			is DaemonReply.Failed -> configureReply
		}
	}

	override suspend fun compile(
		allSources: List<File>,
		changedFiles: List<File>,
	): DaemonReply<CompileOutput> {
		val reply =
			request("compile") {
				add("allSources", allSources.toJsonPaths())
				add("changedFiles", changedFiles.toJsonPaths())
			}
		val response = (reply as? DaemonReply.Ok)?.value
		// Absent field (a daemon predating the signal) stays null - "unknown", which the
		// deploy policy treats conservatively - distinct from an empty list ("nothing").
		val changed =
			(response?.get("classesChanged") as? JsonArray)
				?.mapNotNull { it.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString }
		return reply.mapFile("classesDir") { File(it, "classes") }.mapOk { CompileOutput(it, changed) }
	}

	override suspend fun dex(classesDirs: List<File>): DaemonReply<File> =
		request("dex") {
			add("classesDirs", classesDirs.toJsonPaths())
		}.mapFile("dexFile") { File(it, "classes.dex") }

	override suspend fun relink(
		resDirs: List<File>,
		manifest: File,
	): DaemonReply<File> =
		request("relink") {
			add("resDirs", resDirs.toJsonPaths())
			addProperty("manifest", manifest.absolutePath)
		}.mapFile("resourcesArsc") { File(it, "res/resources.arsc") }

	override suspend fun ping(): Boolean = request("ping") {} is DaemonReply.Ok

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
	 * location under the configured outDir when the daemon omits the field. The field
	 * names are the assumed cross-agent contract; the fallback keeps a field-name
	 * mismatch from becoming a hard failure.
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
			is DaemonReply.BuildFailed -> this
			is DaemonReply.Failed -> this
		}

	private fun <T, R> DaemonReply<T>.mapOk(transform: (T) -> R): DaemonReply<R> =
		when (this) {
			is DaemonReply.Ok -> DaemonReply.Ok(transform(value))
			is DaemonReply.BuildFailed -> this
			is DaemonReply.Failed -> this
		}

	private fun List<File>.toJsonPaths(): JsonArray = JsonArray().also { array -> forEach { array.add(it.absolutePath) } }

	companion object {
		private val log = LoggerFactory.getLogger(DaemonProcessClient::class.java)

		/** Compile of a large changeset can be slow on low-spec; be generous. */
		const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 300_000L

		private const val SHUTDOWN_TIMEOUT_MILLIS = 3_000L
	}
}
