package com.itsaky.androidide.jdwp

import android.util.Log
import com.sun.jdi.Bootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Debugger functions for debugging Java/Kotlin code in applications.
 *
 * @author Akash Yadav
 */
object JdwpDebugger {
    private const val TAG = "JdwpDebugger"

    /**
     * Whether the debugger is enabled or not.
     */
    const val JDWP_ENABLED = true

    /**
     * The port on which the debugger will listen for connections.
     */
    const val DEFAULT_JDWP_PORT = 42233

    /**
     * The timeout duration for waiting for a VM to connect to the debugger.
     */
    val DEFAULT_JDWP_TIMEOUT = 60.seconds

    /**
     * Options for configuring the JDWP agent in a VM.
     */
    val JDWP_OPTIONS_MAP = mapOf<String, String>(
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
    val JDWP_OPTIONS = JDWP_OPTIONS_MAP
        .map { (key, value) -> "$key=$value" }
        .joinToString(",")

    /**
     * The argument provided to JDI [Connector][com.sun.jdi.connect.Connector] to provide the port to listen at.
     */
    val CONNECTOR_PORT = "port"

    /**
     * The argument provided to JDI [Connector][com.sun.jdi.connect.Connector] to provide the timeout
     * to wait for a VM to connect.
     */
    val CONNECTOR_TIMEOUT = "timeout"

    private var _jdwpState: JdwpState? = null
    private val jdwpStateGuard = Mutex()

    /**
     * The current state of the debugger.
     *
     * This is initially `null`, set only when a VM is connected and reset to `null`
     * when the VM exits for any reason.
     */
    val jdwpState: JdwpState?
        get() = _jdwpState?.copy(breakpoints = listOf(*_jdwpState!!.breakpoints.toTypedArray()))

    /**
     * Start listening for connections from VMs.
     *
     * **This function will suspend until a connection is established or the timeout is reached.**
     *
     * @param port The port to listen at.
     * @param timeout The timeout to wait for the debuggable application to connect. This can be set
     *          to `0` to listen indefinitely.
     * @return The state of the debugger if the connection was successful, `null` otherwise.
     */
    suspend fun startListening(
        port: Int = DEFAULT_JDWP_PORT,
        timeout: Duration = DEFAULT_JDWP_TIMEOUT,
    ): JdwpState? = withContext(Dispatchers.Default) {
        Log.d(TAG, "startListening: port=${port} timeout=${timeout.toString(DurationUnit.MILLISECONDS)}")

        val vmm = Bootstrap.virtualMachineManager()
        val connector = vmm.listeningConnectors().firstOrNull()
            ?: throw IllegalStateException("No listening connectors found")

        val args = connector.defaultArguments()
        args[CONNECTOR_PORT]!!.setValue(port.toString())
        args[CONNECTOR_TIMEOUT]!!.setValue(timeout.inWholeMilliseconds.toString())

        // offload the connection to a background IO thread
        val vm = withContext(Dispatchers.IO) {
            Log.d(TAG, "startListening: waiting for connection...")
            connector.accept(args)
        }

        Log.d(TAG, "startListening: connected to '${vm.name()}'")
        jdwpStateGuard.withLock {
            _jdwpState = JdwpState(vmm, vm, mutableListOf())
        }

        return@withContext this@JdwpDebugger.jdwpState
    }
}