package com.itsaky.androidide.lsp

import android.annotation.SuppressLint
import com.itsaky.androidide.eventbus.events.EventReceiver
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.lsp.debug.IDebugEventHandler
import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.events.BreakpointHitEvent
import com.itsaky.androidide.lsp.debug.events.BreakpointHitResponse
import com.itsaky.androidide.lsp.debug.events.StepEvent
import com.itsaky.androidide.lsp.debug.events.StoppedEvent
import com.itsaky.androidide.lsp.debug.model.BreakpointRequest
import com.itsaky.androidide.lsp.debug.model.LocatableEvent
import com.itsaky.androidide.lsp.debug.model.Location
import com.itsaky.androidide.lsp.debug.model.ResumePolicy
import com.itsaky.androidide.lsp.debug.model.ThreadListRequestParams
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
object IDEDebugClientImpl : IDebugClient, IDebugEventHandler, EventReceiver {

    var viewModel: DebuggerViewModel? = null
    private val logger = LoggerFactory.getLogger(IDEDebugClientImpl::class.java)

    @OptIn(DelicateCoroutinesApi::class)
    private val clientContext = newFixedThreadPoolContext(4, "IDEDebugClient")
    private val clientScope = CoroutineScope(clientContext + SupervisorJob())
    private val clients = CopyOnWriteArraySet<RemoteClient>()

    val breakpoints = BreakpointHandler()

    val requireClient: RemoteClient
        get() = clients.first()

    val clientOrNull: RemoteClient?
        get() = clients.firstOrNull()

    init {
        register()
        breakpoints.begin { breakpoints ->
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
        clientScope.launch {
            val adapter = event.remoteClient.adapter
            val threadResponse = adapter.allThreads(
                ThreadListRequestParams(
                    remoteClient = event.remoteClient
                )
            )

            val threads = threadResponse.threads
            if (threads.isEmpty()) {
                logger.error("Failed to get info about thread: {}", event.threadId)
                return@launch
            }

            logger.debug("threads({}): {}", threads.size, threads)
            viewModel?.setThreads(threads)

            openLocation(event)
        }
        return BreakpointHitResponse(
            remoteClient = event.remoteClient,
            resumePolicy = ResumePolicy.SUSPEND_THREAD
        )
    }

    override fun onStep(event: StepEvent) {
        logger.debug("onStep: {}", event)
    }

    override fun onAttach(client: RemoteClient) {
        logger.debug("onAttach: client={}", client)

        check(client !in clients) {
            "Already attached to client"
        }

        clients += client
        viewModel?.onAttach()
        breakpoints.unhighlightHighlightedLocation()

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

    override fun onStop(event: StoppedEvent) {
        logger.debug("onStop: {}", event)
        // program has stopped execution for some reason
    }

    override fun onDisconnect(client: RemoteClient) {
        logger.debug("onDisconnect: client={}", client)
        if (clients.size == 1 && clients.first() == client) {
            // reset debugger UI
            viewModel?.setThreads(emptyList())
        }

        breakpoints.unhighlightHighlightedLocation()
        clients -= client
        viewModel?.onDetach()

        Unit
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