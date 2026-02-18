package com.itsaky.androidide.tooling.api.messages

import java.io.Serializable

/**
 * A build identifier.
 *
 * @author Akash Yadav
 */
data class BuildId(
	val buildSessionId: String,
	val buildId: Long,
): Serializable {
	companion object {
		val Unknown = BuildId("unknown", -1)
	}
}
