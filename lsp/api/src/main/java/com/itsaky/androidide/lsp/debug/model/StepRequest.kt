package com.itsaky.androidide.lsp.debug.model

import com.itsaky.androidide.lsp.debug.RemoteClient

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