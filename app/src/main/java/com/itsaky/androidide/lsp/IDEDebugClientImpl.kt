package com.itsaky.androidide.lsp

import androidx.annotation.GuardedBy
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.lsp.debug.IDebugEventHandler
import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.events.StoppedEvent
import com.itsaky.androidide.lsp.debug.events.BreakpointHitEvent
import com.itsaky.androidide.lsp.debug.events.BreakpointHitResponse
import com.itsaky.androidide.lsp.debug.events.ResumePolicy
import com.itsaky.androidide.lsp.debug.model.BreakpointRequest
import com.itsaky.androidide.lsp.debug.model.Source
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.lsp.java.JavaLanguageServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

/**
 * State of [IDEDebugClientImpl].
 */
data class DebugClientState(
    val clients: Set<RemoteClient>,
    val breakpoints: Set<PositionalBreakpoint>,
)

/**
 * @author Akash Yadav
 */
object IDEDebugClientImpl : IDebugClient, IDebugEventHandler {

    private val logger = LoggerFactory.getLogger(IDEDebugClientImpl::class.java)

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val clientContext = newSingleThreadContext("IDEDebugClient")
    private val clientScope = CoroutineScope(clientContext + SupervisorJob())
    private val stateGuard = ReentrantReadWriteLock()

    @GuardedBy("stateGuard")
    private var state = DebugClientState(
        clients = mutableSetOf(),
        breakpoints = mutableSetOf()
    )

    private val testBreakpoint = PositionalBreakpoint(
        source = Source(
            "DebuggingTarget.java",
            "/storage/emulated/0/AndroidIDEProjects/My Application1/app/src/main/java/com/itsaky/debuggable/DebuggingTarget.java"
        ),
        line = 27,
    )

    private var hitCount = 0
    override fun onBreakpointHit(event: BreakpointHitEvent): BreakpointHitResponse {
        logger.debug("onBreakpointHit: {}", event)
        ++hitCount

        if (hitCount == 2) {
            val server = ILanguageServerRegistry.getDefault()
                .getServer(JavaLanguageServer.SERVER_ID) as JavaLanguageServer
            runBlocking {
                event.remoteClient.adapter.removeBreakpoints(
                    BreakpointRequest(
                        breakpoints = listOf(testBreakpoint)
                    )
                )
            }
        }

        return BreakpointHitResponse(
            remoteClient = event.remoteClient,
            resumePolicy = ResumePolicy.RESUME_CLIENT
        )
    }

    override fun onAttach(client: RemoteClient): Unit = stateGuard.write {
        logger.debug("onAttach: client={}", client)

        check(client !in state.clients) {
            "Already attached to client"
        }

        state = state.copy(clients = state.clients + client)

        clientScope.launch {
            client.adapter.addBreakpoints(
                BreakpointRequest(
                    breakpoints = listOf(testBreakpoint)
                )
            )
        }
    }

    override fun onStop(event: StoppedEvent) {
        logger.debug("onStop: {}", event)
        // program has stopped execution for some reason
    }

    override fun onTerminate(client: RemoteClient) = stateGuard.write {
        logger.debug("onTerminate: client={}", client)
        state = state.copy(clients = state.clients - client)
    }

    override fun onDeath(client: RemoteClient) = stateGuard.write {
        logger.debug("onDeath: client={}", client)
        state = state.copy(clients = state.clients - client)
    }
}