package com.itsaky.androidide.lsp.debug

import androidx.annotation.StringRes

/**
 * Result of connecting a [IDebugAdapter] to a [IDebugClient].
 */
sealed class DebugClientConnectionResult {

	/**
	 * The connection was successful.
	 */
	data object Success: DebugClientConnectionResult()

	/**
	 * The connection failed.
	 *
	 * @property context Additional context about the error.
	 */
	data class Failure(
		val context: String? = null,
		@field:StringRes val contextRes: Int? = null,
		val cause: Throwable? = null,
	): DebugClientConnectionResult()
}