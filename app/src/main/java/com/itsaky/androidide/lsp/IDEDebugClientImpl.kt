package com.itsaky.androidide.lsp

import androidx.annotation.GuardedBy
import com.itsaky.androidide.lsp.debug.IDebugClient
import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.events.StoppedEvent
import com.itsaky.androidide.lsp.debug.model.SourceBreakpoint
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