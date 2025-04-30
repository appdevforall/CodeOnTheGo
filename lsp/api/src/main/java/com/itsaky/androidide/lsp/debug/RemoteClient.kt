package com.itsaky.androidide.lsp.debug

/**
 * Metadata about the remove client being debugged.
 *
 * @property name The name of the client.
 * @property version The version of the client.
 * @property capabilities The capabilities of the client.
 * @property adapter The debug adapter to which this client is connected to.
 *
 * @author Akash Yadav
 */
data class RemoteClient(
    val name: String,
    val version: String,
    val capabilities: RemoteClientCapabilities,
    val adapter: IDebugAdapter,
)

/**
 * Capabilities of the remote client.
 *
 * @property breakpointSupport Whether the remote client supports setting/unsetting breakpoints.
 * @property stepSupport Whether the remote client can step through code execution.
 */
data class RemoteClientCapabilities(
    val breakpointSupport: Boolean,
    val stepSupport: Boolean,
)
