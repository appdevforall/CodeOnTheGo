package com.itsaky.androidide.lsp.java.debug

import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Options for the Java debugger.
 *
 * @author Akash Yadav
 */
object JdwpOptions {

	private val logger = LoggerFactory.getLogger(JdwpOptions::class.java)

	/**
	 * Whether the debugger is enabled or not.
	 */
	const val JDWP_ENABLED = true

	/**
	 * The port on which the debugger will listen for connections. Set to `0`
	 * to allow the system to pick up an ephemeral port for listening to
	 * incoming JDWP connections.
	 */
	const val DEFAULT_JDWP_PORT = 0

	/**
	 * The timeout duration for waiting for a VM to connect to the debugger. The default value
	 * is to wait indefinitely.
	 */
	val DEFAULT_JDWP_TIMEOUT = 0.seconds

	/**
	 * Options for configuring the JDWP agent in a VM.
	 */
	val JDWP_OPTIONS_MAP = mapOf(
		"suspend" to "n",
		"server" to "n",
		"transport" to "dt_socket",
	)

	/**
	 * The argument provided to JDI [Connector][com.sun.jdi.connect.Connector] to provide the port to listen at.
	 */
	const val CONNECTOR_PORT = "port"

	/**
	 * The argument provided to JDI [Connector][com.sun.jdi.connect.Connector] to provide the timeout
	 * to wait for a VM to connect.
	 */
	const val CONNECTOR_TIMEOUT = "timeout"

	/**
	 * The port at which the debug adapter is currently listening for
	 * JDWP connections.
	 */
	fun activeJdwpPort(): Int {
		val messagePrefix = "Cannot get current JDWP port."
		val debugAdapter = JavaDebugAdapter.currentInstance()
			?: run {
				logger.error("{} No debug adapter instance found.", messagePrefix)
				return -1
			}

		val listenAddress = debugAdapter.listenerState.listenAddress
			?: run {
				logger.error(
					"{} Debug adapter is not listening for connections.",
					messagePrefix
				)
				return -1
			}

		val port = listenAddress.substringAfterLast(':')
			.toIntOrNull() ?: run {
			logger.error("{} Unable to get port from address: {}", messagePrefix, listenAddress)
			return -1
		}

		return port
	}

	/**
	 * Create the options string for the JDWP agent.
	 *
	 * @param listenPort The port at which the debugger is listening for incoming
	 * connections.
	 * @return A comma-separated list of JDWP JVM TI agent options.
	 */
	fun createJdwpAgentOptions(listenPort: Int): String {
		val options = JDWP_OPTIONS_MAP + ("address" to listenPort.toString())
		return options.map { (key, value) -> "$key=$value" }.joinToString(",")
	}
}