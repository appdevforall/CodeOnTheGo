package org.appdevforall.codeonthego.indexing.service

/**
 * Thrown when an index is requested from, or registered with, an [IndexRegistry] that has been closed.
 *
 * Its project session has ended, so callers treat it as an expected end of their work rather than
 * a failure.
 */
class IndexRegistryClosedException(
	message: String,
) : IllegalStateException(message)
