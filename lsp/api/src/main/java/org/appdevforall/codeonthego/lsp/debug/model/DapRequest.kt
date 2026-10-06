package org.appdevforall.codeonthego.lsp.debug.model

import org.appdevforall.codeonthego.lsp.debug.RemoteClient

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