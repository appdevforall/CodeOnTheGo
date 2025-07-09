package com.itsaky.androidide.lsp.java.debug

import androidx.annotation.WorkerThread
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.lsp.debug.IDebugAdapter
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.RemoteClientCapabilities
import com.itsaky.androidide.lsp.debug.events.BreakpointHitEvent
import com.itsaky.androidide.lsp.debug.model.BreakpointRequest
import com.itsaky.androidide.lsp.debug.model.BreakpointResponse
import com.itsaky.androidide.lsp.debug.model.BreakpointResult
import com.itsaky.androidide.lsp.debug.model.MethodBreakpoint
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.lsp.debug.model.StepRequestParams
import com.itsaky.androidide.lsp.debug.model.StepResponse
import com.itsaky.androidide.lsp.debug.model.StepResult
import com.itsaky.androidide.lsp.debug.model.ThreadInfoRequestParams
import com.itsaky.androidide.lsp.debug.model.ThreadInfoResponse
import com.itsaky.androidide.lsp.debug.model.ThreadInfoResult
import com.itsaky.androidide.lsp.debug.model.ThreadListRequestParams
import com.itsaky.androidide.lsp.debug.model.ThreadListResponse
import com.itsaky.androidide.lsp.java.JavaLanguageServer
import com.itsaky.androidide.lsp.java.debug.spec.BreakpointSpec
import com.itsaky.androidide.lsp.java.debug.utils.EvaluationContext
import com.itsaky.androidide.lsp.java.debug.utils.asDepthInt
import com.itsaky.androidide.lsp.java.debug.utils.asJdiInt
import com.itsaky.androidide.lsp.java.debug.utils.asLspLocation
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.utils.withStopWatch
import com.sun.jdi.Bootstrap
import com.sun.jdi.ThreadReference
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.VirtualMachine
import com.sun.jdi.connect.TransportTimeoutException
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.StepEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.StepRequest
import com.sun.tools.jdi.SocketListeningConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArraySet
import com.itsaky.androidide.lsp.debug.events.StepEvent as LspStepEvent

/**
 * @author Akash Yadav
 */
internal class JavaDebugAdapter : IDebugAdapter, EventConsumer, AutoCloseable {

    private val vmm = Bootstrap.virtualMachineManager()
    private val vms = CopyOnWriteArraySet<VmConnection>()
    private val adapterScope = CoroutineScope(Dispatchers.Default)

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
    fun evalContext() = connVm().evalContext

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
                breakpointSupport = true,
                stepSupport = true,
                threadInfoSupport = true,
                threadListSupport = true,
                suspensionSupport = true,
                killSupport = true,
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
            eventHandler = eventHandler
        )

        // Start listening for events
        vmConnection.startEventHandler()

        // get initial threads AFTER we start listening for events so that we always have references
        // to all available threads
        threadState.initThreads()

        this.vms.add(vmConnection)
        this._listenerState!!.client.onAttach(client)
    }

    override suspend fun connectedRemoteClients(): Set<RemoteClient> =
        vms.map(VmConnection::client).toSet()

    override suspend fun suspendClient(client: RemoteClient) = doSuspensionIfEnabled(client) { vm ->
        try {
            vm.vm.suspend()
            true
        } catch (e: Throwable) {
            logger.error("Failed to suspend VM '{}'", vm.client.name, e)
            false
        }
    } ?: false

    override suspend fun resumeClient(client: RemoteClient) = doSuspensionIfEnabled(client) { vm ->
        try {
            logger.debug("resuming client: {}", client.name)
            vm.vm.resume()
            true
        } catch (e: Throwable) {
            logger.error("Failed to suspend VM '{}'", vm.client.name, e)
            false
        }
    } ?: false

    private suspend inline fun <T> doSuspensionIfEnabled(
        client: RemoteClient,
        crossinline action: (VmConnection) -> T
    ): T? = withContext(Dispatchers.IO) {
        val vm = connVm()

        check(vm.client == client) {
            "Received request to suspend client=$client, but the current client is ${vm.client}"
        }

        if (!vm.isHandlingEvents || !vm.client.capabilities.suspensionSupport) {
            logger.debug("Suspension support is not enabled, or the VM is not handling events")
            return@withContext null
        }

        action(vm)
    }

    override suspend fun killClient(client: RemoteClient) = withContext(Dispatchers.IO) {
        val vm = connVm()

        check(vm.client == client) {
            "Received request to kill client=$client, but the current client is ${vm.client}"
        }

        if (!vm.isHandlingEvents || !vm.client.capabilities.suspensionSupport) {
            logger.debug("Restart support is not enabled, or the VM is not handling events")
            return@withContext false
        }

        return@withContext try {
            vm.vm.exit(1)
            true
        } catch (e: Throwable) {
            logger.error("Failed to kill client: {}", client.name, e)
            false
        }
    }

    override suspend fun setBreakpoints(request: BreakpointRequest): BreakpointResponse =
        withContext(Dispatchers.IO) {
            val vm = connVm()

            check(vm.client == request.remoteClient) {
                "Received request to set breakpoints in client=${request.remoteClient}, but the current client is ${vm.client}"
            }

            if (!vm.isHandlingEvents || !vm.client.capabilities.breakpointSupport) {
                // we're not handling events from the VM, or the VM does not support adding breakpoints
                logger.warn("Breakpoint support is not enabled, or the VM is not handling events")
                return@withContext BreakpointResponse.EMPTY
            }

            val specList = vm.eventRequestSpecList!!
            val allSpecs = specList.eventRequestSpecs()
                .filterIsInstance<BreakpointSpec>()

            allSpecs.forEach { spec ->
                try {
                    spec.remove(vm.vm)
                } catch (e: Throwable) {
                    logger.error("failed to remove breakpoint {}", spec)
                }
            }

            return@withContext BreakpointResponse(request.breakpoints.map { breakpoint ->
                logger.debug("add breakpoint {}", breakpoint)

                val qualifiedName =
                    ProjectManagerImpl.getInstance().rootProject?.subProjects?.filterIsInstance<ModuleProject>()
                        ?.firstNotNullOfOrNull { module ->
                            module.compileJavaSourceClasses
                                .findSource(Paths.get(breakpoint.source.path))?.qualifiedName
                        }

                logger.debug("qualified name: {}", qualifiedName)

                val spec = when (breakpoint) {
                    is PositionalBreakpoint -> specList.createBreakpoint(
                        source = breakpoint.source,
                        // +1 because we receive 0-indexed line numbers from the IDE
                        // while JDI expects 1-index line numbers
                        lineNumber = breakpoint.line + 1,
                        qualifiedName = qualifiedName,
                        suspendPolicy = breakpoint.suspendPolicy.asJdiInt(),
                    )

                    is MethodBreakpoint -> specList.createBreakpoint(
                        source = breakpoint.source,
                        methodId = breakpoint.methodId,
                        methodArgs = breakpoint.methodArgs,
                        qualifiedName = qualifiedName,
                        suspendPolicy = breakpoint.suspendPolicy.asJdiInt()
                    )

                    else -> throw IllegalArgumentException("Unsupported breakpoint type: $breakpoint")
                }

                val result = kotlin.runCatching {
                    specList.addEagerlyResolve(spec, rethrow = true)
                }

                val failure = result.exceptionOrNull()
                val resolveSuccess = result.getOrDefault(false)

                when {
                    resolveSuccess && spec.isResolved -> BreakpointResult.Success(breakpoint, false)
                    resolveSuccess && !spec.isResolved -> BreakpointResult.Success(breakpoint, true)
                    else -> BreakpointResult.Failure(breakpoint, failure)
                }
            })
        }

    override suspend fun step(request: StepRequestParams): StepResponse =
        withContext(Dispatchers.IO) {
            logger.debug("step: {}", request)

            val vm = connVm()

            check(vm.client == request.remoteClient) {
                "Received request to step in client=${request.remoteClient}, but the current client is ${vm.client}"
            }

            if (!vm.isHandlingEvents || !vm.client.capabilities.stepSupport) {
                // we're not handling events from the VM, or the VM does not support adding breakpoints
                return@withContext StepResponse(StepResult.Failure("Step support is not enabled"))
            }

            val suspendedThread = vm.threadState.current
                ?: return@withContext StepResponse(StepResult.Failure("No thread is currently suspended"))

            // Verify thread is actually suspended
            val suspendCount = suspendedThread.thread.suspendCount()
            logger.debug("Thread {} suspend count: {}", suspendedThread.thread.name(), suspendCount)

            if (suspendCount == 0) {
                return@withContext StepResponse(StepResult.Failure("Thread is not suspended"))
            }

            logger.debug("Step {} thread {}", request.type, suspendedThread.thread.name())

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

            req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
            req.addCountFilter(request.countFilter)
            req.enable()
            suspendedThread.thread.resume()
            vm.threadState.invalidateAll()

            return@withContext StepResponse(StepResult.Success)
        }

    override suspend fun threadInfo(
        request: ThreadInfoRequestParams
    ): ThreadInfoResponse {
        val vm = connVm()

        check(vm.client == request.remoteClient) {
            "Received request for thread info from client=${request.remoteClient}, but the current client is ${vm.client}"
        }

        if (!vm.isHandlingEvents || !vm.client.capabilities.threadInfoSupport) {
            return ThreadInfoResponse(ThreadInfoResult.Failure("ThreadInfo support is not enabled"))
        }

        val threadInfo = vm.threadState.getThreadInfo(request.threadId)
        if (threadInfo != null) {
            return ThreadInfoResponse(ThreadInfoResult.Success(threadInfo.asLspModel()))
        }

        return ThreadInfoResponse(ThreadInfoResult.Failure())
    }

    override suspend fun allThreads(request: ThreadListRequestParams): ThreadListResponse =
        withContext(Dispatchers.IO) {
            val vm = connVm()
            check(vm.client == request.remoteClient) {
                "Received request to list threads in client=${request.remoteClient}, but the current client is ${vm.client}"
            }

            if (!vm.isHandlingEvents || !vm.client.capabilities.threadListSupport) {
                return@withContext ThreadListResponse(emptyList())
            }

            return@withContext withStopWatch("create thread list") {
                ThreadListResponse(
                    threads = vm.threadState.threads.map {
                            thread -> LspThreadInfo(thread)
                    }
                )
            }
        }

    private fun clearPreviousStep(vm: VirtualMachine, thread: ThreadReference) {
        val reqMgr = vm.eventRequestManager()
        for (stepReq in reqMgr.stepRequests()) {
            if (stepReq.thread() == thread) {
                stepReq.disable()
                reqMgr.deleteEventRequest(stepReq)
            }
        }
    }

    override fun breakpointEvent(e: BreakpointEvent) {
        e.virtualMachine().checkIsCurrentVm()

        val vm = connVm()
        val location = e.location()
        val thread = e.thread()

        logger.debug(
            "breakpoint hit in thread {} at {} (suspendCount={})",
            thread.name(),
            location,
            thread.suspendCount()
        )

        listenerState.client.onBreakpointHit(
            event = BreakpointHitEvent(
                remoteClient = vm.client,
                location = location.asLspLocation(),
                threadId = thread.uniqueID().toString()
            )
        )
    }

    override fun stepEvent(e: StepEvent) {
        logger.debug("stepEvent: {}", e)
        e.virtualMachine().checkIsCurrentVm()

        val vm = connVm()
        val location = e.location()
        val thread = e.thread()

        listenerState.client.onStep(
            event = LspStepEvent(
                remoteClient = vm.client,
                location = location.asLspLocation(),
                threadId = thread.uniqueID().toString(),
            )
        )
    }

    override fun vmDisconnectEvent(e: VMDisconnectEvent) {
        logger.debug("vmDisconnectedEvent: {}", e)
        if (!isConnected()) {
            logger.warn("Got VM disconnect event when not connected")
            return
        }

        e.virtualMachine().checkIsCurrentVm()
        val vm = connVm()

        try {
            // notify client that the VM has disconnected
            _listenerState?.client?.onDisconnect(vm.client)
        } catch (err: Throwable) {
            logger.error("Failed to notify client of VM disconnect", err)
        }

        try {
            vm.close()
        } catch (err: Throwable) {
            if (err !is VMDisconnectedException) {
                logger.error("Failed to disconnect from VM '{}'", vm.client.name, err)
            }
        } finally {
            vms.remove(vm)
        }
    }

    override fun close() {
        try {
            _listenerState?.stopListening()
            listenerThread?.interrupt()
        } catch (err: Throwable) {
            logger.error("Unable to stop VM connection listener", err)
        }

        adapterScope.launch(Dispatchers.IO) {
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
    }

    private fun isConnected() = vms.isNotEmpty()

    private fun checkIsConnected() = check(isConnected()) {
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
                logger.debug("Waiting for VM connection")
                onConnect(listenerState.accept())
            } catch (timeout: TransportTimeoutException) {
                logger.warn("Timeout waiting for VM connection")
            } catch (err: Throwable) {
                logger.error("An error occurred while listening for VM connections", err)
            }
        }
    }
}
