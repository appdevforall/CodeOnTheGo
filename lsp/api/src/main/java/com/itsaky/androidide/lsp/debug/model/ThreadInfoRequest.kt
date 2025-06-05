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
data class ThreadInfoRequestParams(
    val threadId: String,
    override val remoteClient: RemoteClient,
): DapRequest

/**
 * Response to a [ThreadInfo] request.
 *
 * @property result The result of the request, may be `null`.
 * @author Akash Yadav
 */
data class ThreadInfoResponse(
    val result: ThreadInfoResult?,
): DapResponse

/**
 * Request the adapter to list all the threads of the given VM.
 */
data class ThreadListRequestParams(
    override val remoteClient: RemoteClient
): DapRequest

/**
 * Response to a [ThreadListRequestParams].
 *
 * @param threads All known threads of the VM.
 */
data class ThreadListResponse(
    val threads: List<ThreadInfo>
): DapResponse

