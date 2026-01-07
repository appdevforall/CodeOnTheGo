/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.services.builder

import ch.qos.logback.core.CoreConstants
import com.itsaky.androidide.logging.JvmStdErrAppender
import com.itsaky.androidide.shell.executeProcessAsync
import com.itsaky.androidide.tasks.cancelIfActive
import com.itsaky.androidide.tasks.ifCancelledOrInterrupted
import com.itsaky.androidide.tooling.api.IToolingApiClient
import com.itsaky.androidide.tooling.api.IToolingApiServer
import com.itsaky.androidide.tooling.api.util.ToolingApiLauncher
import com.itsaky.androidide.utils.Environment
import com.termux.shared.reflection.ReflectionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Runner thread for the Tooling API.
 *
 * @author Akash Yadav
 */
internal class ToolingServerRunner(
	private var listener: OnServerStartListener?,
	private var observer: Observer?,
) {
	internal var pid: Int? = null
	private var job: Job? = null
	private var _isStarted = AtomicBoolean(false)

	var isStarted: Boolean
		get() = _isStarted.get()
		private set(value) {
			_isStarted.set(value)
		}

	private val runnerScope = CoroutineScope(Dispatchers.IO + CoroutineName("ToolingServerRunner"))

	companion object {
		private val log = LoggerFactory.getLogger(ToolingServerRunner::class.java)

		/**
		 * Whether to enable logging of the error stream of the tooling server.
		 */
		const val TOOLING_ERR_STREAM_LOGGING_ENABLED = true

		/**
		 * Whether to enable force killing the Gradle daemon.
		 */
		const val TOOLING_DAEMON_KILL_ENABLED = true

		/**
		 * Timeout for killing the tooling daemon. The tooling API waits for this timeout before
		 * forcibly killing the daemon process tree if it's still alive.
		 */
		val TOOLING_DAEMON_KILL_TIMEOUT = 3.seconds

		/**
		 * Timeout for the shell process used to kill the daemon. Must always be greater
		 * than [TOOLING_DAEMON_KILL_TIMEOUT].
		 */
		val TOOLING_DAEMON_KILL_SHELL_TIMEOUT = TOOLING_DAEMON_KILL_TIMEOUT + 2.seconds
	}

	fun setListener(listener: OnServerStartListener?) {
		this.listener = listener
	}

	fun startAsync(envs: Map<String, String>) =
		runnerScope
			.launch {
				var process: Process?
				try {
					log.info("Starting tooling API server...")
					val command =
						listOf(
							Environment.JAVA.absolutePath, // The 'java' binary executable
							// Allow reflective access to private members of classes in the following
							// packages:
							// - java.lang
							// - java.io
							// - java.util
							//
							// If any of the model classes in 'tooling-api-model' module send/receive
							// objects from the JDK, their package name must be declared here with
							// '--add-opens' to prevent InaccessibleObjectException.
							// For example, some of the model classes has members of type java.io.File.
							// When sending/receiving these type of objects using LSP4J, members of
							// these objects are reflectively accessed by Gson. If we do no specify
							// '--add-opens' for 'java.io' (for java.io.File) package, JVM will throw an
							// InaccessibleObjectException.
							"--add-opens",
							"java.base/java.lang=ALL-UNNAMED",
							"--add-opens",
							"java.base/java.util=ALL-UNNAMED",
							"--add-opens",
							"java.base/java.io=ALL-UNNAMED", // The JAR file to run
							"-D${CoreConstants.STATUS_LISTENER_CLASS_KEY}=com.itsaky.androidide.tooling.impl.util.LogbackStatusListener",
							"-D${JvmStdErrAppender.PROP_JVM_STDERR_APPENDER_ENABLED}=${TOOLING_ERR_STREAM_LOGGING_ENABLED}",
							"-jar",
							Environment.TOOLING_API_JAR.absolutePath,
						)

					process =
						executeProcessAsync {
							this.command = command

							// input and output is used for communication to the tooling server
							// error stream is used to read the server logs
							this.redirectErrorStream = false
							this.workingDirectory = null // HOME
							this.environment = envs
						}

					pid =
						ReflectionUtils
							.getDeclaredField(process::class.java, "pid")
							?.get(process) as Int?
					pid ?: throw IllegalStateException("Unable to get process ID")

					log.info("Tooling API server running with PID: {}", pid)

					val inputStream = process.inputStream
					val outputStream = process.outputStream
					val errorStream = process.errorStream

					val processJob =
						launch(Dispatchers.IO) {
							try {
								process?.waitFor()
								log.info(
									"Tooling API process exited with code : {}",
									process?.exitValue() ?: "<unknown>",
								)
								process = null
							} finally {
								log.info("Destroying Tooling API process...")
								if (TOOLING_DAEMON_KILL_ENABLED) {
									killWithDescendants(pid!!.toLong())
								} else {
									process?.destroyForcibly()
								}
							}
						}

					val launcher =
						ToolingApiLauncher.newClientLauncher(
							observer!!.getClient(),
							inputStream,
							outputStream,
						)

					val future = launcher.startListening()
					observer?.onListenerStarted(
						server = launcher.remoteProxy as IToolingApiServer,
						errorStream = errorStream,
					)

					isStarted = true

					listener?.onServerStarted(pid!!)

					// we don't need the listener anymore
					// also, this might be a reference to the activity
					// release to prevent memory leak
					listener = null

					// Wait(block) until the process terminates
					val serverJob =
						launch(Dispatchers.IO) {
							try {
								future.get()
							} catch (err: Throwable) {
								err.ifCancelledOrInterrupted {
									log.info("ToolingServerThread has been cancelled or interrupted.")
								}

								// rethrow the error
								throw err
							}
						}

					joinAll(serverJob, processJob)
				} catch (e: Throwable) {
					if (e !is CancellationException) {
						log.error("Unable to start tooling API server", e)
					}
				}
			}.also {
				job = it
			}

	fun release() {
		this.listener = null
		this.observer = null
		this.job?.cancel(CancellationException("Cancellation was requested"))
		this.runnerScope.cancelIfActive("Cancellation was requested")
	}

	private suspend fun killWithDescendants(pid: Long) {
		val cmd = mutableListOf<String>()
		var shell = System.getenv("SHELL")
		if (shell.isNullOrBlank()) {
			shell = "/system/bin/sh"
		}

		val shellFile = File(shell)
		if (!shellFile.exists()) {
			throw FileNotFoundException("Shell file does not exist: $shell")
		}

		if (!shellFile.canExecute() && !shellFile.setExecutable(true)) {
			throw RuntimeException("Failed to set shell file as executable: $shell")
		}

		cmd.add(shell)
		cmd.add("-c")

		cmd.add(
			listOf(
				"pkill -HUP -P $pid", // send SIGHUP to all descendants of $pid
				"sleep ${TOOLING_DAEMON_KILL_TIMEOUT.inWholeSeconds}", // wait for descendants to exit
				"pkill -KILL -P $pid", // kill all descendants of $pid, forcibly
				"kill -KILL $pid", // kill $pid, forcibly
			).joinToString(separator = ";"),
		)

		log.info("Running {} to kill process {} with descendants", cmd, pid)

		val proc =
			ProcessBuilder(cmd)
				.run {
					redirectErrorStream(true)
					start()
				}

		try {
			val exitCode = withContext(Dispatchers.IO) {
				proc.waitFor(
					TOOLING_DAEMON_KILL_SHELL_TIMEOUT.inWholeMilliseconds,
					TimeUnit.MILLISECONDS
				)
			}

			val output =
				proc.inputStream.use { inputStream -> inputStream.bufferedReader().readText() }
			log.info(
				"Result of killing process tree of pid={}: exitCode={} {}",
				pid,
				exitCode,
				output
			)
		} catch (_: TimeoutException) {
			log.error("Killing process tree of pid={} timed out", pid)
			proc.destroyForcibly()
		}
	}

	interface Observer {
		fun onListenerStarted(
			server: IToolingApiServer,
			errorStream: InputStream,
		)

		fun onServerExited(exitCode: Int)

		fun getClient(): IToolingApiClient
	}

	/** Callback to listen for Tooling API server start event.  */
	fun interface OnServerStartListener {
		/** Called when the tooling API server has been successfully started.  */
		fun onServerStarted(pid: Int)
	}
}
