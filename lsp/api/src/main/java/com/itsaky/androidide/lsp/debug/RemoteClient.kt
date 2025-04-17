package com.itsaky.androidide.lsp.debug

/**
 * Metadata about the remove client being debugged.
 *
 * @property name The name of the client.
 * @property version The version of the client.
 * @property capabilities The capabilities of the client.
 *
 * @author Akash Yadav
 */
data class RemoteClient(
    val name: String,
    val version: String,
    val capabilities: RemoteClientCapabilities,
)

/**
 * Capabilities of the remote client.
 *
 * @property canSetBreakpoints Whether the remote client can set breakpoints.
 */
data class RemoteClientCapabilities(
    val canSetBreakpoints: Boolean,
)
