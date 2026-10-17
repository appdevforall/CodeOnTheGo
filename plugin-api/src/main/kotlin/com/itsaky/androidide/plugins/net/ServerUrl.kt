package com.itsaky.androidide.plugins.net

/**
 * The server an `http`/`https` URL points at, and where that server lives.
 *
 * Here so every plugin that connects to a user-configured server judges an address the same way;
 * what each [NetworkLocality] is allowed to do (cleartext, credentials) stays that plugin's call.
 *
 * Built only by [parse], which reads the URL as typed and never resolves a name or touches the
 * network, so it is safe on the main thread while the user types. The flip side: a public name that
 * happens to resolve to `127.0.0.1` is still [NetworkLocality.PUBLIC], the safe reading for a
 * cleartext check. Only the origin is kept; path, query and fragment are not.
 *
 * Not a data class: its public `copy` would let a caller build a URL [parse] never vetted, such as
 * an out-of-range port.
 */
class ServerUrl internal constructor(
	val scheme: Scheme,
	private val address: Host,
	/** The explicit port, or null when the URL names none. */
	val port: Int?,
) {
	/**
	 * @property id the scheme as written in a URL
	 * @property isEncrypted whether traffic is protected from anyone on the path
	 */
	enum class Scheme(
		val id: String,
		val isEncrypted: Boolean,
	) {
		HTTP("http", isEncrypted = false),
		HTTPS("https", isEncrypted = true),
	}

	/** Lowercased, without IPv6 brackets or zone id. */
	val host: String get() = address.text

	val locality: NetworkLocality get() = address.locality

	/** True when anyone on the path can read the traffic. */
	val isCleartext: Boolean get() = !scheme.isEncrypted

	override fun equals(other: Any?): Boolean = other is ServerUrl && scheme == other.scheme && host == other.host && port == other.port

	override fun hashCode(): Int = listOf(scheme, host, port).hashCode()

	/** The canonical origin, e.g. `http://[::1]:8080`. */
	override fun toString(): String =
		buildString {
			append(scheme.id).append(SCHEME_SEPARATOR).append(address.urlForm)
			port?.let { append(PORT_SEPARATOR).append(it) }
		}

	companion object {
		internal const val SCHEME_SEPARATOR = "://"
		internal const val PORT_SEPARATOR = ':'

		/**
		 * @param input the URL as typed; surrounding whitespace is ignored
		 */
		@JvmStatic
		fun parse(input: String?): ServerUrlParseResult = ServerUrlParser.parse(input)
	}
}
