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
import com.itsaky.androidide.lsp.debug.model.BreakpointResult
import com.itsaky.androidide.lsp.debug.model.MethodBreakpoint
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.lsp.debug.model.Source
import com.itsaky.androidide.lsp.debug.model.StepRequestParams
import com.itsaky.androidide.lsp.debug.model.StepResponse
import com.itsaky.androidide.lsp.debug.model.StepResult
import com.itsaky.androidide.lsp.java.JavaLanguageServer
import com.itsaky.androidide.lsp.java.debug.spec.BreakpointSpec
import com.itsaky.androidide.lsp.java.debug.utils.asDepthInt
import com.itsaky.androidide.lsp.java.debug.utils.asJdiInt
import com.sun.jdi.Bootstrap
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import com.sun.jdi.connect.TransportTimeoutException
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.request.StepRequest
import com.sun.tools.jdi.SocketListeningConnector
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArraySet

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

        private val DEFAULT_CLASS_EXCLUSION_FILTERS = arrayOf(
            "java.*",
            "javax.*",
            "jdk.*",
            "com.sun.*",
            "sun.*",
        )

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
            client = client, connector = connector, args = args
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

        val vmCanBeModfified = vm.canBeModified()
        val client = RemoteClient(
            adapter = this,
            name = vm.name(),
            version = vm.version(),
            capabilities = RemoteClientCapabilities(
                breakpointSupport = vmCanBeModfified,
                stepSupport = vmCanBeModfified
            ),
        )

        logger.debug("Connected to VM: {}", client)

        val threadState = ThreadState(vm = vm)

        val eventHandler = if (vm.canBeModified()) {
            EventHandler(
                vm = vm,
                threadState = threadState,
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
            threadState = threadState,
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

        check(vm.client == request.remoteClient) {
            "Received request for breakpoints from a different client"
        }

        if (!vm.isHandlingEvents || !vm.client.capabilities.breakpointSupport) {
            // we're not handling events from the VM, or the VM does not support adding breakpoints
            return BreakpointResponse.EMPTY
        }

        val specList = vm.eventRequestSpecList!!
        val allSpecs = specList.eventRequestSpecs()
            .filterIsInstance<BreakpointSpec>()
        return BreakpointResponse(request.breakpoints.map { br ->

            var addSpec = false
            var spec = allSpecs
                .firstOrNull { spec ->
                    spec.isSameAsDef(br)
                }

            if (spec == null) {
                addSpec = true

                spec = when (br) {
                    is PositionalBreakpoint -> specList.createBreakpoint(
                        source = br.source,
                        lineNumber = br.line,
                        suspendPolicy = br.suspendPolicy.asJdiInt()
                    )

                    is MethodBreakpoint -> specList.createBreakpoint(
                        source = br.source,
                        methodId = br.methodId,
                        methodArgs = br.methodArgs,
                        suspendPolicy = br.suspendPolicy.asJdiInt()
                    )

                    else -> throw IllegalArgumentException("Unsupported breakpoint type: $br")
                }
            }

            var failure: Throwable? = null
            val resolveSuccess = if (addSpec) {
                // TODO: Maybe get the cause of the failure from this?
                specList.addEagerlyResolve(spec)
            } else {
                try {
                    spec.resolveEagerly(vm.vm)
                    true
                } catch (err: Throwable) {
                    failure = err
                    false
                }
            }

            when {
                resolveSuccess && spec.isResolved -> BreakpointResult.Added(br, false)
                resolveSuccess && !spec.isResolved -> BreakpointResult.Added(br, true)
                else -> BreakpointResult.Failure(br, failure)
            }
        })
    }

    override suspend fun removeBreakpoints(
        request: BreakpointRequest
    ): BreakpointResponse {
        val vm = connVm()

        check(vm.client == request.remoteClient) {
            "Received request for breakpoints from a different client"
        }

        if (!vm.isHandlingEvents || !vm.client.capabilities.breakpointSupport) {
            // we're not handling events from the VM, or the VM does not support adding breakpoints
            return BreakpointResponse.EMPTY
        }

        val specList = vm.eventRequestSpecList!!
        val allSpecs = specList.eventRequestSpecs()
            .filterIsInstance<BreakpointSpec>()

        return BreakpointResponse(
            results = request.breakpoints.flatMap { br ->
                allSpecs
                    .filter { spec -> spec.isSameAsDef(br) }
                    .map { spec ->
                        try {
                            spec.remove(vm.vm)
                            logger.debug("Removed breakpoint: {}", spec)
                            BreakpointResult.Removed(br)
                        } catch (err: Throwable) {
                            logger.error("Failed to remove breakpoint: {}", spec, err)
                            BreakpointResult.Failure(br, err)
                        }
                    }
            }
        )
    }

    override suspend fun step(request: StepRequestParams): StepResponse {
        val vm = connVm()

        check(vm.client == request.remoteClient) {
            "Received request for breakpoints from a different client"
        }

        if (!vm.isHandlingEvents || !vm.client.capabilities.stepSupport) {
            // we're not handling events from the VM, or the VM does not support adding breakpoints
            return StepResponse(StepResult.Failure("Step support is not enabled"))
        }

        val suspendedThread = vm.threadState.current
            ?: return StepResponse(StepResult.Failure("No thread is currently suspended"))

        clearPreviousStep(vm.vm, suspendedThread.thread)

        val reqMgr = vm.vm.eventRequestManager()
        val req = reqMgr.createStepRequest(
            suspendedThread.thread,
            StepRequest.STEP_LINE,
            request.type.asDepthInt()
        )

        for (pattern in DEFAULT_CLASS_EXCLUSION_FILTERS) {
            req.addClassExclusionFilter(pattern)
        }

        req.addCountFilter(request.countFilter)
        req.enable()
        vm.threadState.invalidateAll()
        vm.vm.resume()

        return StepResponse(StepResult.Success)
    }

    private fun clearPreviousStep(vm: VirtualMachine, thread: ThreadReference) {
        val reqMgr = vm.eventRequestManager()
        for (stepReq in reqMgr.stepRequests()) {
            if (stepReq.thread() == thread) {
                reqMgr.deleteEventRequest(stepReq)
                break
            }
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
                remoteClient = vm.client, descriptor = descriptor
            )
        )

        response.resumePolicy.doResume(vm.vm, e.thread())
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

    private fun ResumePolicy.doResume(
        vm: VirtualMachine,
        thread: ThreadReference? = null
    ) = when (this) {
        ResumePolicy.SUSPEND_THREAD -> {
            logger.debug("ResumePolicy.SUSPEND_THREAD -> suspend thread")
            checkNotNull(thread) { "Cannot suspend thread without a thread reference" }.suspend()
        }

        ResumePolicy.RESUME_THREAD -> {
            logger.debug("ResumePolicy.RESUME_THREAD -> resume thread")
            checkNotNull(thread) { "Cannot resume thread without a thread reference" }.resume()
        }

        ResumePolicy.SUSPEND_CLIENT -> {
            logger.debug("ResumePolicy.SUSPEND -> keep suspended")
            vm.suspend()
        }

        ResumePolicy.RESUME_CLIENT -> {
            logger.debug("ResumePolicy.RESUME_CLIENT -> resume client")
            vm.resume()
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

internal class JDWPListenerThread(
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
