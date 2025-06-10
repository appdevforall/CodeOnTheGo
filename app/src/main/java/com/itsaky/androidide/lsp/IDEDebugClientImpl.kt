package com.itsaky.androidide.lsp

import androidx.annotation.GuardedBy
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.lsp.debug.IDebugEventHandler
import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.events.BreakpointHitEvent
import com.itsaky.androidide.lsp.debug.events.BreakpointHitResponse
import com.itsaky.androidide.lsp.debug.events.StepEvent
import com.itsaky.androidide.lsp.debug.events.StoppedEvent
import com.itsaky.androidide.lsp.debug.model.BreakpointRequest
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.lsp.debug.model.ResumePolicy
import com.itsaky.androidide.lsp.debug.model.Source
import com.itsaky.androidide.lsp.debug.model.StepRequestParams
import com.itsaky.androidide.lsp.debug.model.StepResult
import com.itsaky.androidide.lsp.debug.model.StepType
import com.itsaky.androidide.lsp.debug.model.ThreadListRequestParams
import com.itsaky.androidide.viewmodel.DebuggerConnectionState
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * State of [IDEDebugClientImpl].
 */
data class DebugClientState(
    val clients: Set<RemoteClient>,
    val breakpoints: HashMap<Int, PositionalBreakpoint>,
) {
    val client: RemoteClient
        get() = clients.first()

    val clientOrNull: RemoteClient?
        get() = clients.firstOrNull()
}

/**
 * @author Akash Yadav
 */
object IDEDebugClientImpl : IDebugClient, IDebugEventHandler {

    var viewModel: DebuggerViewModel? = null
    private val logger = LoggerFactory.getLogger(IDEDebugClientImpl::class.java)

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val clientContext = newSingleThreadContext("IDEDebugClient")
    private val clientScope = CoroutineScope(clientContext + SupervisorJob())
    private val stateGuard = ReentrantReadWriteLock()

    @GuardedBy("stateGuard")
    private var state = DebugClientState(
        clients = mutableSetOf(),
        breakpoints = hashMapOf()
    )

    fun stepOver() = doStep(type = StepType.Over)
    fun stepInto() = doStep(type = StepType.Into)
    fun stepOut() = doStep(type = StepType.Out)

    private inline fun withClient(action: String, block: (RemoteClient) -> Unit) = stateGuard.read {
        state.clientOrNull?.also(block) ?: logger.error("Cannot perform $action action. Not connected to a remote client.")
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
            logger.debug("step response: {}", response)
            if (response.result == StepResult.Success) {
                viewModel?.setConnectionState(DebuggerConnectionState.AWAITING)
            }
        }
    }

    fun toggleBreakpoint(file: File, line: Int) = stateGuard.write {
        val breakpoint = PositionalBreakpoint(
            source = Source(
                name = file.name,
                path = file.absolutePath
            ),
            line = line
        )

        val remove = state.breakpoints.containsKey(line)
        if (remove) {
            state.breakpoints.remove(line)
        } else {
            state.breakpoints[line] = breakpoint
        }

        // if we're already connected to a client, update the client as well
        state.clientOrNull?.also { client ->
            if (!client.capabilities.breakpointSupport) {
                logger.error("Remote client does not support breakpoints")
                return@write
            }

            clientScope.launch {
                val adapter = client.adapter
                val request = BreakpointRequest(
                    remoteClient = client,
                    breakpoints = listOf(breakpoint),
                )

                if (remove) {
                    adapter.removeBreakpoints(request)
                } else {
                    adapter.addBreakpoints(request)
                }
            }
        }
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
        }
        return BreakpointHitResponse(
            remoteClient = event.remoteClient,
            resumePolicy = ResumePolicy.SUSPEND_THREAD
        )
    }

    override fun onStep(event: StepEvent) {
        logger.debug("onStep: {}", event)
    }

    override fun onAttach(client: RemoteClient): Unit = stateGuard.write {
        logger.debug("onAttach: client={}", client)

        check(client !in state.clients) {
            "Already attached to client"
        }

        state = state.copy(clients = state.clients + client)
        viewModel?.setConnectionState(DebuggerConnectionState.ATTACHED)

        clientScope.launch {
            client.adapter.addBreakpoints(
                BreakpointRequest(
                    remoteClient = client,
                    breakpoints = state.breakpoints.values.toList()
                )
            )
        }
    }

    override fun onStop(event: StoppedEvent) {
        logger.debug("onStop: {}", event)
        // program has stopped execution for some reason
    }

    override fun onDisconnect(client: RemoteClient) = stateGuard.write {
        logger.debug("onDisconnect: client={}", client)
        if (state.clients.size == 1 && state.clients.first() == client) {
            // reset debugger UI
            viewModel?.setThreads(emptyList())
        }

        state = state.copy(clients = state.clients - client)
        viewModel?.setConnectionState(DebuggerConnectionState.DETACHED)

        Unit
    }

    fun pauseResumeVM() = withClient("pause/resume VM") { client ->
        // Por ahora, implementación placeholder
        clientScope.launch {
            // TODO: Implementar según los métodos disponibles en client.adapter
            // Posibles opciones:
            // - client.adapter.suspend() / client.adapter.resume()
            // - O usar toggleBreakpoint para pausar/reanudar
            logger.debug("pauseResumeVM called - implementation pending")
        }
    }

    fun stopVM() = withClient("stop VM") { client ->
        clientScope.launch {
            // TODO: Implementar según los métodos disponibles en client.adapter
            // Posibles opciones:
            // - client.adapter.disconnect()
            // - client.adapter.terminate()
            logger.debug("stopVM called - implementation pending")
        }
    }

    fun restartVM() = withClient("restart VM") { client ->
        clientScope.launch {
            // TODO: Implementar según los métodos disponibles en client.adapter
            // Posibles opciones:
            // - client.adapter.disconnect() seguido de reconnect
            // - O un método específico de restart si existe
            logger.debug("restartVM called - implementation pending")
        }
    }

}