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
package com.itsaky.androidide.tooling.impl

import com.itsaky.androidide.tooling.api.IToolingApiClient
import com.itsaky.androidide.tooling.api.util.ToolingApiLauncher.newServerLauncher
import org.gradle.tooling.events.OperationType
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.system.exitProcess

object Main {
	private val logger = LoggerFactory.getLogger(Main::class.java)

	@Volatile var client: IToolingApiClient? = null
	@Volatile var future: Future<Void?>? = null

	fun checkGradleWrapper() {
		logger.info("Checking gradle wrapper availability...")
		try {
			if (client?.checkGradleWrapperAvailability()?.get()?.isAvailable != true) {
				logger.warn(
					("Gradle wrapper is not available."
							+ " Client might have failed to ensure availability."
							+ " Build might fail.")
				)
			} else {
				logger.info("Gradle wrapper is available")
			}
		} catch (e: Throwable) {
			logger.warn("Unable to get Gradle wrapper availability from client", e)
		}
	}

	@JvmStatic
	fun main(args: Array<String>) {
		// disable the JVM std.err appender

//		System.setProperty(JvmStdErrAppender.PROP_JVM_STDERR_APPENDER_ENABLED, "false")

		logger.debug("Starting Tooling API server...")

		val server = ToolingApiServerImpl()
		val executorService = Executors.newCachedThreadPool()
		val launcher = newServerLauncher(server, System.`in`, System.out, executorService)

		val future = launcher.startListening()
		val client = launcher.getRemoteProxy() as IToolingApiClient

		this.client = client
		this.future = future

		server.connect(client)

		logger.debug("Server started. Will run until shutdown message is received...")
		logger.debug("Running on Java version: {}", System.getProperty("java.version", "<unknown>"))

		try {
			future.get()
		} catch (_: CancellationException) {
			// ignored
		} catch (e: InterruptedException) {
			logger.error("Main thread interrupted while waiting for shutdown message", e)
			Thread.currentThread().interrupt()
		} catch (e: ExecutionException) {
			logger.error("An error occurred while waiting for shutdown message", e)
		} finally {
			// Cleanup should be performed in ToolingApiServerImpl.shutdown()
			// this is to make sure that the daemons are stopped in case the client doesn't call shutdown()
			try {
				if (server.isInitialized || server.isConnected) {
					logger.warn("Connection to tooling server closed without shutting it down!")
					server.shutdown().get()
				}

				executorService.shutdownNow()
			} catch (e: InterruptedException) {
				logger.error("An error occurred while shutting down tooling API server", e)
			} catch (e: Throwable) {
				logger.error("An error occurred while shutting down tooling API server", e)
			} finally {
				Main.client = null
				Main.future = null

				runCatching {
					future.cancel(true)
				}.onFailure { error ->
					logger.error("Failed to cancel launcher future", error)
				}

				logger.info("Tooling API server shutdown complete")
				exitProcess(0)
			}
		}
	}

	fun progressUpdateTypes(): MutableSet<OperationType?> {
		val types = HashSet<OperationType?>()

		// AndroidIDE currently does not handle any other type of events
		types.add(OperationType.TASK)
		types.add(OperationType.PROJECT_CONFIGURATION)

		return types
	}
}
