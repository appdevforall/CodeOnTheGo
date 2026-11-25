package com.itsaky.androidide.sqlitedebugger

/** Checks of the values an app's SQLite debugger broadcasts to Code on the Go (ADFA-7137). */
object DebuggerEndpointValidation {
	const val ACTION = "com.itsaky.androidide.SQLITE_DEBUGGER_ENDPOINT"
	const val EXTRA_TOKEN = "token"
	const val EXTRA_PORT = "port"
	const val EXTRA_SENDER = "sender"

	/** The library's McpHttp.newToken() is URL-safe base64 without padding, so no `=`. */
	private val TOKEN = Regex("[A-Za-z0-9_-]{1,128}")

	data class Endpoint(
		val token: String,
		val port: Int,
	)

	/** Returns null for anything but a token of 1-128 URL-safe base64 characters and a port of 1-65535. */
	fun validate(
		token: String?,
		port: Int?,
	): Endpoint? {
		if (token == null || port == null) return null
		if (!TOKEN.matches(token)) return null
		if (port !in 1..65535) return null
		return Endpoint(token, port)
	}

	/**
	 * The package that created the PendingIntent the app attached as [EXTRA_SENDER]. The framework
	 * sets it, so another app cannot claim a package that is not its own. Null (no PendingIntent)
	 * means ignore the broadcast. Any self-declared package extra is never consulted.
	 */
	fun resolveSender(creatorPackage: String?): String? = creatorPackage?.takeIf { it.isNotBlank() && it.length <= 255 }
}
