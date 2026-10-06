package org.appdevforall.codeonthego.lsp.debug.model

import org.appdevforall.codeonthego.lsp.debug.RemoteClient

/**
 * The type of step request.
 */
enum class StepType {

    /**
     * Step over.
     */
    Over,

    /**
     * Step into.
     */
    Into,

    /**
     * Step out.
     */
    Out,
}

/**
 * Step request parameters.
 */
data class StepRequestParams(
    override val remoteClient: RemoteClient,
    val type: StepType,
    val countFilter: Int = 1,
): DapRequest