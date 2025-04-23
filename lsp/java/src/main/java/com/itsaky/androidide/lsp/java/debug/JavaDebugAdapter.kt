package com.itsaky.androidide.lsp.java.debug

import androidx.annotation.WorkerThread
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.lsp.debug.IDebugAdapter
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.RemoteClientCapabilities
import com.itsaky.androidide.lsp.debug.model.BreakpointRequest
import com.itsaky.androidide.lsp.debug.model.BreakpointResponse
import com.itsaky.androidide.lsp.debug.model.MethodBreakpoint
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.lsp.java.JavaLanguageServer
import com.itsaky.androidide.lsp.java.debug.spec.EventRequestSpec
import com.itsaky.androidide.lsp.java.debug.spec.EventRequestSpecList
import com.sun.jdi.Bootstrap
import com.sun.jdi.VirtualMachine
import com.sun.jdi.connect.Connector
import com.sun.jdi.connect.TransportTimeoutException
import com.sun.tools.jdi.SocketListeningConnector
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArraySet

data class ListenerState(
    val client: IDebugClient,
    val connector: SocketListeningConnector,
    val args: Map<String, Connector.Argument>
) {

    /**
     * Start listening for connections from VMs.
     *
     * @return The address of the listening socket.
     */
    fun startListening(): String = connector.startListening(args)

    /**
     * Stop listening for connections from VMs.
     */
    fun stopListening() = connector.stopListening(args)

    /**
     * Accept a connection from a VM.
     *
     * @return The connected VM.
     */
    fun accept(): VirtualMachine = connector.accept(args)
}

/**
 * Represents a connected VM state.
 *
 * @property client The client model for the VM, containing basic metadata.
 * @property vm The connected VM.
 * @property eventReader The job that reads events from the VM.
 */
internal data class VmConnection(
    val client: RemoteClient,
    val vm: VirtualMachine,
    val eventHandler: EventHandler,
    val eventRequestSpecList: EventRequestSpecList,
) : AutoCloseable {

    @WorkerThread
    override fun close() {
        eventHandler.close()
        vm.exit(0)
    }
}

/**
 * @author Akash Yadav
 */
internal class JavaDebugAdapter : IDebugAdapter, EventConsumer, AutoCloseable {

    private val vmm = Bootstrap.virtualMachineManager()
    private val vms = CopyOnWriteArraySet<VmConnection>()

    private var listenerThread: JDWPListenerThread? = null
    private var listenerState: ListenerState? = null

    companion object {
        private val logger = LoggerFactory.getLogger(JavaDebugAdapter::class.java)

        /**
         * Get the current instance of the [JavaDebugAdapter].
         */
        fun currentInstance(): JavaDebugAdapter? {
            val lsp = ILanguageServerRegistry.getDefault().getServer(JavaLanguageServer.SERVER_ID)
            return ((lsp as? JavaLanguageServer?)?.debugAdapter as? JavaDebugAdapter?)
        }

        /**
         * Get the current instance of the [JavaDebugAdapter], or throw an [IllegalStateException] if
         * the current instance is `null`.
         */
        inline fun requireInstance(
            message: () -> String = {
                "Unable to get current instance of JavaDebugAdapter"
            }
        ): JavaDebugAdapter = checkNotNull(currentInstance(), message)
    }

    fun connVm() = this.vms.first()

    /**
     * Get the connected VM.
     */
    fun vm() = connVm().vm

    override fun connectDebugClient(client: IDebugClient) {
        val connector = vmm.listeningConnectors().firstOrNull() as? SocketListeningConnector?
        if (connector == null) {
            logger.error("No listening connectors found, or the connector is not a SocketListeningConnector")
            return
        }

        val args = connector.defaultArguments()
        args[JdwpOptions.CONNECTOR_PORT]!!.setValue(JdwpOptions.DEFAULT_JDWP_PORT.toString())
        args[JdwpOptions.CONNECTOR_TIMEOUT]!!.setValue(JdwpOptions.DEFAULT_JDWP_TIMEOUT.inWholeMilliseconds.toString())

        logger.debug(
            "Starting JDWP listener. Arguments={}",
            args.map { (_, value) -> "$value" }.joinToString()
        )

        this.listenerState = ListenerState(
            client = client, connector = connector, args = args
        )

        this.listenerThread = JDWPListenerThread(
            listenerState!!, this::onConnectedToVm
        ).also { thread -> thread.start() }
    }

    @WorkerThread
    @Synchronized
    private fun onConnectedToVm(vm: VirtualMachine) {
        if (vms.isNotEmpty()) {
            // TODO: Maybe add support for debugging multiple VMs?
            throw UnsupportedOperationException("Debugging multiple VMs is not supported yet")
        }

        val client = RemoteClient(
            adapter = this,
            name = vm.name(),
            version = vm.version(),
            capabilities = RemoteClientCapabilities(
                canSetBreakpoints = true
            ),
        )

        logger.debug("Connected to VM: {}", client)

        val eventRequestSpecList = EventRequestSpecList(vm)
        val eventHandler = EventHandler(
            vm = vm,
            eventRequestSpecList = eventRequestSpecList,
            stopOnVmStart = false,
            consumer = this
        )

        val vmConnection = VmConnection(
            client = client,
            vm = vm,
            eventHandler = eventHandler,
            eventRequestSpecList = EventRequestSpecList(vm)
        )

        this.vms.add(vmConnection)
        this.listenerState!!.client.onAttach(client)
    }

    override suspend fun connectedRemoteClients(): Set<RemoteClient> =
        vms.map(VmConnection::client).toSet()

    override suspend fun addBreakpoints(
        request: BreakpointRequest
    ): BreakpointResponse {
        val vm = connVm()

        for (br in request.breakpoints) {
            val spec = when (br) {
                is PositionalBreakpoint -> vm.eventRequestSpecList.createBreakpoint(
                    request.source,
                    br.line
                )

                is MethodBreakpoint -> vm.eventRequestSpecList.createBreakpoint(
                    request.source,
                    br.methodId,
                    br.methodArgs
                )

                else -> throw IllegalArgumentException("Unsupported breakpoint type: $br")
            }

            resolveNow(vm, spec)
        }

        return BreakpointResponse(emptyList())
    }

    override suspend fun removeBreakpoints(
        request: BreakpointRequest
    ): BreakpointResponse {
        TODO("Not yet implemented")
    }

    fun resolveNow(vm: VmConnection, spec: EventRequestSpec) {
        val success = vm.eventRequestSpecList.addEagerlyResolve(spec)
        if (success && !spec.isResolved) {
            logger.info("Deferring: {}", spec)
        }
    }

    override fun close() {
        try {
            listenerState?.stopListening()
            listenerThread?.interrupt()
        } catch (err: Throwable) {
            logger.error("Unable to stop VM connection listener", err)
        }

        while (vms.isNotEmpty()) {
            val vm = vms.first()
            try {
                vm.close()
            } catch (err: Throwable) {
                logger.error("Failed to exit VM '{}'", vm.client.name, err)
            } finally {
                vms.remove(vm)
            }
        }
    }
}

class JDWPListenerThread(
    private val listenerState: ListenerState,
    private val onConnect: (VirtualMachine) -> Unit
) : Thread("JDWPListenerThread") {

    companion object {
        private val logger = LoggerFactory.getLogger(JDWPListenerThread::class.java)
    }

    override fun run() {
        listenerState.startListening()
        while (isAlive && !isInterrupted) {
            try {
                onConnect(listenerState.accept())
            } catch (timeout: TransportTimeoutException) {
                logger.warn("Timeout waiting for VM connection")
            } catch (err: Throwable) {
                logger.error("An error occurred while listening for VM connections", err)
            }
        }
    }
}
