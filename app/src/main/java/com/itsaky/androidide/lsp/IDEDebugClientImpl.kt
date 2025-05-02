package com.itsaky.androidide.lsp

import androidx.annotation.GuardedBy
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.lsp.debug.IDebugEventHandler
import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.events.BreakpointHitEvent
import com.itsaky.androidide.lsp.debug.events.BreakpointHitResponse
import com.itsaky.androidide.lsp.debug.events.StepEvent
import com.itsaky.androidide.lsp.debug.model.ResumePolicy
import com.itsaky.androidide.lsp.debug.events.StoppedEvent
import com.itsaky.androidide.lsp.debug.model.BreakpointRequest
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.lsp.debug.model.Source
import com.itsaky.androidide.lsp.debug.model.ThreadInfoParams
import com.itsaky.androidide.lsp.debug.model.ThreadInfoResult
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

        runBlocking {
            val threadInfo = event.remoteClient.adapter.threadInfo(
                request = ThreadInfoParams(
                    threadId = event.threadId,
                    remoteClient = event.remoteClient
                )
            )

            if (threadInfo.result !is ThreadInfoResult.Success) {
                logger.error("Failed to get thread info from remote client")
            } else {
                val ti = (threadInfo.result as ThreadInfoResult.Success).threadInfo
                val currentFrame = ti.getFrames()[0]
                val someInt = currentFrame.getVariables().first { it.name == "someInt" }
                logger.debug("onBreakpointHit[preSetValue]: someInt={}, value={}", someInt, someInt.getValue())
                someInt.setValue("42")
                logger.debug("onBreakpointHit[postSetValue]: someInt={}, value={}", someInt, someInt.getValue())
            }
        }

        if (hitCount == 2) {
            runBlocking {
                event.remoteClient.adapter.removeBreakpoints(
                    BreakpointRequest(
                        remoteClient = event.remoteClient,
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

    override fun onStep(event: StepEvent) {
        logger.debug("onStep: {}", event)
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
                    remoteClient = client,
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