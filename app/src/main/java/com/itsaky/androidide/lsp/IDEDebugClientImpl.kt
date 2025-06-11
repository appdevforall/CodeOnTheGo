package com.itsaky.androidide.lsp

import android.annotation.SuppressLint
import com.itsaky.androidide.eventbus.events.EventReceiver
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.lsp.debug.IDebugEventHandler
import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.events.BreakpointHitEvent
import com.itsaky.androidide.lsp.debug.events.BreakpointHitResponse
import com.itsaky.androidide.lsp.debug.events.StepEvent
import com.itsaky.androidide.lsp.debug.model.BreakpointRequest
import com.itsaky.androidide.lsp.debug.model.LocatableEvent
import com.itsaky.androidide.lsp.debug.model.Location
import com.itsaky.androidide.lsp.debug.model.ResumePolicy
import com.itsaky.androidide.lsp.debug.model.StepRequestParams
import com.itsaky.androidide.lsp.debug.model.StepResult
import com.itsaky.androidide.lsp.debug.model.StepType
import com.itsaky.androidide.lsp.debug.model.ThreadListRequestParams
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.viewmodel.DebuggerConnectionState
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * @author Akash Yadav
 */
class IDEDebugClientImpl(
    private val viewModel: DebuggerViewModel,
) : IDebugClient, IDebugEventHandler, EventReceiver {

    private val logger = LoggerFactory.getLogger(IDEDebugClientImpl::class.java)

    @OptIn(DelicateCoroutinesApi::class)
    private val clientContext = newFixedThreadPoolContext(4, "IDEDebugClient")
    private val clientScope = CoroutineScope(clientContext + SupervisorJob())
    private val clients = CopyOnWriteArraySet<RemoteClient>()
    val breakpoints = BreakpointHandler()

    companion object {
        @JvmStatic
        fun getInstance() = Lookup.getDefault().lookup(IDEDebugClientImpl::class.java)

        @JvmStatic
        fun requireInstance() = checkNotNull(getInstance()) {
            "Cannot lookup IDEDebugClientImpl"
        }
    }

    val connectionStateFlow: StateFlow<DebuggerConnectionState>
        get() = viewModel.connectionState

    var connectionState: DebuggerConnectionState
        get() = viewModel.connectionState.value
        private set(value) {
            logger.debug("move to connection state: {}", value)
            viewModel.setConnectionState(value)
        }

    private val clientOrNull: RemoteClient?
        get() = clients.firstOrNull()

    /**
     * Returns true if the client is connected.
     *
     * @return `true` if the client is connected, `false` otherwise.
     */
    fun isVmConnected() = connectionState >= DebuggerConnectionState.ATTACHED

    /**
     * Returns true if the client is connected and suspended.
     *
     * The VM may or may not be able to view/alter its state. Check [connectionState]
     * to get the actual state.
     *
     * @return `true` if the client is connected and suspended, `false` otherwise.
     */
    fun isVmSuspended() = connectionState >= DebuggerConnectionState.SUSPENDED

    fun suspendVm() = withClient("suspend vm") { client ->
        if (!client.capabilities.suspensionSupport) {
            logger.error("Remote client does not support suspending")
            return@withClient
        }

        if (isVmSuspended()) {
            logger.warn("Ignoring attempt to suspend VM when it is already suspended")
            return@withClient
        }

        logger.debug("suspending client: {}", client.name)
        clientScope.launch {
            if (client.adapter.suspendClient(client)) {
                connectionState = DebuggerConnectionState.SUSPENDED
            }
        }
    }

    fun resumeVm() = withClient("resume vm") { client ->
        if (!client.capabilities.suspensionSupport) {
            logger.error("Remote client does not support resuming")
            return@withClient
        }

        if (!isVmSuspended()) {
            logger.warn("Ignoring attempt to resume VM when it is not suspended")
            return@withClient
        }

        logger.debug("resuming client: {}", client.name)
        clientScope.launch {
            if (client.adapter.resumeClient(client)) {
                connectionState = DebuggerConnectionState.ATTACHED
            }
        }
    }

    fun killVm() = withClient("kill vm") { client ->
        if (!client.capabilities.killSupport) {
            logger.error("Remote client does not support killing debug application")
            return@withClient
        }

        logger.debug("killing client: {}", client.name)
        clientScope.launch { client.adapter.killClient(client) }
    }

    fun stepOver() = doStep(type = StepType.Over)
    fun stepInto() = doStep(type = StepType.Into)
    fun stepOut() = doStep(type = StepType.Out)

    private inline fun withClient(action: String, block: (RemoteClient) -> Unit) {
        clientOrNull?.also(block)
            ?: logger.error("Cannot perform $action action. Not connected to a remote client.")
    }

    private fun doStep(
        type: StepType,
        countFilter: Int = 1
    ) = withClient("step $type") { client ->
        if (!client.capabilities.stepSupport) {
            logger.error("Remote client does not support stepping")
            return@withClient
        }

        clientScope.launch {
            val params = StepRequestParams(
                remoteClient = client,
                type = type,
                countFilter = countFilter
            )

            val response = client.adapter.step(params)
            if (response.result != StepResult.Success) {
                logger.error("Failed to perform step action, result={}", response.result)
            }
        }
    }

    init {
        register()
        breakpoints.begin { breakpoints ->

            // if we're already connected to a client, update the client as well
            clientOrNull?.also { client ->
                if (!client.capabilities.breakpointSupport) {
                    logger.error("Remote client does not support breakpoints")
                    return@also
                }

                clientScope.launch {
                    clientOrNull?.also { client ->
                        client.adapter.setBreakpoints(
                            BreakpointRequest(
                                remoteClient = client,
                                breakpoints = breakpoints
                            )
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("ImplicitSamInstance")
    @Suppress("UNUSED")
    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onContentChange(event: DocumentChangeEvent) {
        clientScope.launch { breakpoints.change(event) }
    }

    fun toggleBreakpoint(file: File, line: Int) {
        clientScope.launch { breakpoints.toggle(file, line) }
    }

    override fun onBreakpointHit(event: BreakpointHitEvent): BreakpointHitResponse {
        logger.debug("onBreakpointHit: {}", event)

        clientScope.launch {
            updateThreadInfo(event.remoteClient)
            connectionState = DebuggerConnectionState.AWAITING_BREAKPOINT

            openLocation(event)
        }

        return BreakpointHitResponse(
            remoteClient = event.remoteClient,
            resumePolicy = ResumePolicy.SUSPEND_THREAD
        )
    }

    override fun onStep(event: StepEvent) {
        logger.debug("onStep: {}", event)

        clientScope.launch {
            updateThreadInfo(event.remoteClient)
            connectionState = DebuggerConnectionState.AWAITING_BREAKPOINT

            openLocation(event)
        }
    }

    private suspend fun updateThreadInfo(client: RemoteClient) {
        val adapter = client.adapter
        val threadResponse = adapter.allThreads(
            ThreadListRequestParams(
                remoteClient = client
            )
        )

        val threads = threadResponse.threads
        if (threads.isEmpty()) {
            logger.error("Failed to get info about active threads in VM: {}", client.name)
            return
        }

        viewModel.setThreads(threads)
    }

    override fun onAttach(client: RemoteClient) {
        logger.debug("onAttach: client={}", client)

        check(client !in clients) {
            "Already attached to client"
        }

        clients += client
        connectionState = DebuggerConnectionState.ATTACHED
        breakpoints.unhighlightHighlightedLocation()
        viewModel.setThreads(emptyList())

        clientScope.launch {
            val breakpoints = breakpoints.allBreakpoints
            client.adapter.setBreakpoints(
                BreakpointRequest(
                    remoteClient = client,
                    breakpoints = breakpoints
                )
            )
        }
    }

    override fun onDisconnect(client: RemoteClient) {
        logger.debug("onDisconnect: client={}", client)
        if (clients.size == 1 && clients.first() == client) {
            // reset debugger UI
            viewModel.setThreads(emptyList())
        }

        breakpoints.unhighlightHighlightedLocation()
        clients -= client
        connectionState = DebuggerConnectionState.DETACHED
    }

    private suspend fun openLocation(event: LocatableEvent) = openLocation(event.location)

    private suspend fun openLocation(location: Location) {
        val file = location.source.path
        val position = Position(location.line, 0)

        val activity = IDELanguageClientImpl.getInstance().activity

        if (activity == null) {
            logger.error("Cannot open {}:{} because activity is null", file, position.line)
            return
        }

        breakpoints.highlightLocation(file, position.line)

        withContext(Dispatchers.Main.immediate) {
            activity.openFileAndSelect(
                file = File(file),
                selection = Range(
                    start = position,
                    end = position
                )
            )
        }
    }
}