package com.itsaky.androidide.lsp.java.debug

import androidx.annotation.WorkerThread
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.lsp.debug.IDebugAdapter
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.RemoteClientCapabilities
import com.itsaky.androidide.lsp.debug.events.BreakpointDescriptor
import com.itsaky.androidide.lsp.debug.events.BreakpointHitEvent
import com.itsaky.androidide.lsp.debug.events.ResumePolicy
import com.itsaky.androidide.lsp.debug.model.BreakpointRequest
import com.itsaky.androidide.lsp.debug.model.BreakpointResponse
import com.itsaky.androidide.lsp.debug.model.MethodBreakpoint
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.lsp.debug.model.Source
import com.itsaky.androidide.lsp.java.JavaLanguageServer
import com.itsaky.androidide.lsp.java.debug.spec.EventRequestSpec
import com.itsaky.androidide.lsp.java.debug.spec.EventRequestSpecList
import com.sun.jdi.Bootstrap
import com.sun.jdi.VirtualMachine
import com.sun.jdi.connect.Connector
import com.sun.jdi.connect.TransportTimeoutException
import com.sun.jdi.event.BreakpointEvent
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
    val eventHandler: EventHandler? = null,
) : AutoCloseable {

    val isHandlingEvents: Boolean
        get() = eventHandler != null

    /**
     * The event request spec list for the VM.
     */
    val eventRequestSpecList: EventRequestSpecList?
        get() = eventHandler?.eventRequestSpecList

    /**
     * Start listening for events from the VM.
     */
    fun startEventHandler() {
        eventHandler?.startListening()
    }

    @WorkerThread
    override fun close() {
        eventHandler?.close()
        vm.dispose()
    }
}

/**
 * @author Akash Yadav
 */
internal class JavaDebugAdapter : IDebugAdapter, EventConsumer, AutoCloseable {

    private val vmm = Bootstrap.virtualMachineManager()
    private val vms = CopyOnWriteArraySet<VmConnection>()

    private var listenerThread: JDWPListenerThread? = null
    private var _listenerState: ListenerState? = null

    private val listenerState: ListenerState
        get() = checkNotNull(_listenerState) {
            "Listener state is not initialized"
        }

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

    private fun connVm(): VmConnection {
        checkIsConnected()
        return this.vms.first()
    }

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

        this._listenerState = ListenerState(
            client = client,
            connector = connector,
            args = args
        )

        this.listenerThread = JDWPListenerThread(
            _listenerState!!, this::onConnectedToVm
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

        val eventHandler = if (vm.canBeModified()) {
            EventHandler(
                vm = vm,
                stopOnVmStart = false,
                consumer = this
            )
        } else {
            logger.warn("Not reading events from VM '{}' because it is read-only", vm.name())
            null
        }

        val vmConnection = VmConnection(
            client = client,
            vm = vm,
            eventHandler = eventHandler,
        )

        // Start listening for events
        vmConnection.startEventHandler()

        this.vms.add(vmConnection)
        this._listenerState!!.client.onAttach(client)
    }

    override suspend fun connectedRemoteClients(): Set<RemoteClient> =
        vms.map(VmConnection::client).toSet()

    override suspend fun addBreakpoints(
        request: BreakpointRequest
    ): BreakpointResponse {
        val vm = connVm()

        if (!vm.isHandlingEvents) {
            // we're not handling events from the VM
            return BreakpointResponse.EMPTY
        }

        val specList = vm.eventRequestSpecList!!

        for (br in request.breakpoints) {
            val spec = when (br) {
                is PositionalBreakpoint -> specList.createBreakpoint(
                    request.source,
                    br.line
                )

                is MethodBreakpoint -> specList.createBreakpoint(
                    request.source,
                    br.methodId,
                    br.methodArgs
                )

                else -> throw IllegalArgumentException("Unsupported breakpoint type: $br")
            }

            resolveNow(specList, spec)
        }

        return BreakpointResponse(emptyList())
    }

    override suspend fun removeBreakpoints(
        request: BreakpointRequest
    ): BreakpointResponse {
        TODO("Find breakpoints using the request param here and remove them using EventRequestSpecList")
    }

    private fun resolveNow(
        specList: EventRequestSpecList,
        spec: EventRequestSpec
    ) {
        val success = specList.addEagerlyResolve(spec)
        if (success && !spec.isResolved) {
            logger.info("Deferring: {}", spec)
        }
    }

    override fun breakpointEvent(e: BreakpointEvent) {
        e.virtualMachine().checkIsCurrentVm()

        val vm = connVm()
        val location = e.location()
        val descriptor = BreakpointDescriptor(
            source = Source(
                name = location.sourceName(),
                path = location.sourcePath()
            ),
            line = location.lineNumber(),
            column = null,
            threadId = e.thread().uniqueID().toString()
        )

        val response = listenerState.client.onBreakpointHit(
            event = BreakpointHitEvent(
                remoteClient = vm.client,
                descriptor = descriptor
            )
        )

        when (response.resumePolicy) {
            ResumePolicy.SUSPEND -> {
                logger.debug("ResumePolicy.SUSPEND -> keep suspended")
            }
            ResumePolicy.RESUME_THREAD -> {
                logger.debug("ResumePolicy.RESUME_THREAD -> resume thread")
                e.thread().resume()
            }
            ResumePolicy.RESUME_CLIENT -> {
                logger.debug("ResumePolicy.RESUME_CLIENT -> resume client")
                vm.vm.resume()
            }
        }
    }

    override fun close() {
        try {
            _listenerState?.stopListening()
            listenerThread?.interrupt()
        } catch (err: Throwable) {
            logger.error("Unable to stop VM connection listener", err)
        }

        while (vms.isNotEmpty()) {
            val vm = vms.first()
            try {
                vm.close()
            } catch (err: Throwable) {
                logger.error("Failed to disconnect from VM '{}'", vm.client.name, err)
            } finally {
                vms.remove(vm)
            }
        }
    }

    private fun checkIsConnected() = check(vms.isNotEmpty()) {
        "No connected VMs"
    }

    private fun VirtualMachine.checkIsCurrentVm() {
        checkIsConnected()
        check(this == connVm().vm) {
            "Received event from VM that is not connected to this adapter"
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
