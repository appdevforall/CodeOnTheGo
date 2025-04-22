package com.itsaky.androidide.lsp

import androidx.annotation.GuardedBy
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.events.StoppedEvent
import com.itsaky.androidide.lsp.debug.model.BreakpointRequest
import com.itsaky.androidide.lsp.debug.model.Source
import com.itsaky.androidide.lsp.debug.model.SourceBreakpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

/**
 * State of [IDEDebugClientImpl].
 */
data class DebugClientState(
    val clients: Set<RemoteClient>,
    val breakpoints: Set<SourceBreakpoint>,
)

/**
 * @author Akash Yadav
 */
object IDEDebugClientImpl : IDebugClient {

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val clientContext = newSingleThreadContext("IDEDebugClient")
    private val clientScope = CoroutineScope(clientContext + SupervisorJob())
    private val stateGuard = ReentrantReadWriteLock()

    @GuardedBy("stateGuard")
    private var state = DebugClientState(
        clients = mutableSetOf(),
        breakpoints = mutableSetOf()
    )

    override fun onAttach(client: RemoteClient): Unit = stateGuard.write {
        check(client !in state.clients) {
            "Already attached to client"
        }

        state = state.copy(clients = state.clients + client)

        clientScope.launch {
            client.adapter.setBreakpoints(
                BreakpointRequest(
                    source = Source(
                        "DebuggingTarget.java",
                        "/storage/emulated/0/AndroidIDEProjects/My Application1/app/src/main/java/com/itsaky/debuggable/DebuggingTarget.java"
                    ),
                    breakpoints = listOf(
                        SourceBreakpoint(
                            line = 27,
                        )
                    )
                )
            )
        }
    }

    override fun onStop(event: StoppedEvent) {
        // program has stopped execution for some reason
    }

    override fun onTerminate(client: RemoteClient) = stateGuard.write {
        state = state.copy(clients = state.clients - client)
    }

    override fun onDeath(client: RemoteClient) = stateGuard.write {
        state = state.copy(clients = state.clients - client)
    }
}