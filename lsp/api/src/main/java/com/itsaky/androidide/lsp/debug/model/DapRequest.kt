package com.itsaky.androidide.lsp.debug.model

import com.itsaky.androidide.lsp.debug.RemoteClient

/**
 * Base interface for all Debug Adapter Protocol (DAP) requests.
 *
 * @author Akash Yadav
 */
interface DapRequest {

    /**
     * The remote client to use for this request.
     */
    val remoteClient: RemoteClient
}