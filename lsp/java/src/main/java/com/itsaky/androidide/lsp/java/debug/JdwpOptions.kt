package com.itsaky.androidide.lsp.java.debug

import kotlin.time.Duration.Companion.seconds

/**
 * Options for the Java debugger.
 *
 * @author Akash Yadav
 */
object JdwpOptions {

    /**
     * Whether the debugger is enabled or not.
     */
    const val JDWP_ENABLED = true

    /**
     * The port on which the debugger will listen for connections.
     */
    const val DEFAULT_JDWP_PORT = 42233

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
        "address" to DEFAULT_JDWP_PORT.toString(),
        "logflags" to "0xfff",
    )

    /**
     * [JDWP_OPTIONS_MAP] converted to a comma-separated string, as expected by the debugger
     * agent.
     */
    val JDWP_OPTIONS = JDWP_OPTIONS_MAP.map { (key, value) -> "$key=$value" }.joinToString(",")

    /**
     * The argument provided to JDI [Connector][com.sun.jdi.connect.Connector] to provide the port to listen at.
     */
    const val CONNECTOR_PORT = "port"

    /**
     * The argument provided to JDI [Connector][com.sun.jdi.connect.Connector] to provide the timeout
     * to wait for a VM to connect.
     */
    const val CONNECTOR_TIMEOUT = "timeout"

}