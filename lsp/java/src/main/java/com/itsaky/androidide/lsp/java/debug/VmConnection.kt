package com.itsaky.androidide.lsp.java.debug

import androidx.annotation.WorkerThread
import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.java.debug.spec.EventRequestSpecList
import com.sun.jdi.VirtualMachine

/**
 * Represents a connected VM state.
 *
 * @property client The client model for the VM, containing basic metadata.
 * @property vm The connected VM.
 * @property eventHandler The event handler for the VM, if it supports events.
 */
internal data class VmConnection(
    val client: RemoteClient,
    val vm: VirtualMachine,
    val threadState: ThreadState = ThreadState(vm),
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