package com.itsaky.androidide.lsp.debug.model

import com.itsaky.androidide.lsp.debug.RemoteClient

/**
 * Result in a [ThreadInfoResponse].
 */
sealed interface ThreadInfoResult {

    /**
     * The thread information was found.
     */
    data class Success(
        val threadInfo: ThreadInfo,
    ): ThreadInfoResult

    /**
     * The thread information was not found.
     */
    data class Failure(
        val cause: Throwable?
    ): ThreadInfoResult {
        constructor(message: String? = null) : this(IllegalStateException(message))
    }
}

/**
 * Parameters for a [ThreadInfo] request.
 *
 * @property threadId The ID of the thread.
 * @author Akash Yadav
 */
data class ThreadInfoParams(
    val threadId: String,
    override val remoteClient: RemoteClient,
): DapRequest

/**
 * Response to a [ThreadInfo] request.
 *
 * @property result The result of the request.
 * @author Akash Yadav
 */
data class ThreadInfoResponse(
    val result: ThreadInfoResult,
): DapResponse
