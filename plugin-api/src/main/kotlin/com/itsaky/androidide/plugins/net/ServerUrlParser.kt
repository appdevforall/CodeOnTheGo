package com.itsaky.androidide.plugins.net

import com.itsaky.androidide.plugins.net.ServerUrlParseResult.Invalid.Reason

/** Splits a URL into scheme, host and port, leaving each host kind to parse its own text. */
internal object ServerUrlParser {
	/** `scheme://authority rest`; the authority ends at the first `/`, `?` or `#`. */
	private val URL_SHAPE = Regex("""([A-Za-z][A-Za-z0-9+.-]*)${Regex.escape(ServerUrl.SCHEME_SEPARATOR)}([^/?#]*).*""")

	/** Separates userinfo from the host; the host is what follows the last one. */
	private const val USERINFO_SEPARATOR = '@'

	/** A port is an unsigned 16-bit number. */
	private val VALID_PORTS = 0..UShort.MAX_VALUE.toInt()

	fun parse(input: String?): ServerUrlParseResult {
		val text = input?.trim().orEmpty()
		if (text.isEmpty()) return invalid(Reason.BLANK)
		if (text.any(Char::isWhitespace)) return invalid(Reason.MALFORMED)

		val (schemeId, authorityText) = URL_SHAPE.matchEntire(text)?.destructured ?: return invalid(Reason.MALFORMED)
		val scheme =
			ServerUrl.Scheme.entries.firstOrNull { it.id.equals(schemeId, ignoreCase = true) }
				?: return invalid(Reason.UNSUPPORTED_SCHEME)

		val authority = Authority.parse(authorityText.substringAfterLast(USERINFO_SEPARATOR)) ?: return invalid(Reason.MALFORMED)
		if (authority.hostText.isEmpty()) return invalid(Reason.NO_HOST)
		val host = authority.parseHost() ?: return invalid(Reason.MALFORMED)

		val port =
			when (val parsed = ParsedPort.parse(authority.portText)) {
				ParsedPort.Absent -> null
				is ParsedPort.Present -> parsed.port
				ParsedPort.Invalid -> return invalid(Reason.MALFORMED)
			}

		return ServerUrlParseResult.Valid(ServerUrl(scheme, host, port))
	}

	private fun invalid(reason: Reason) = ServerUrlParseResult.Invalid(reason)

	/**
	 * An authority's `host[:port]`, split but not yet validated.
	 *
	 * @property portText what follows the port separator, or null when there is none
	 * @property bracketed whether the host was in brackets, which only an IPv6 literal may be
	 */
	private data class Authority(
		val hostText: String,
		val portText: String?,
		val bracketed: Boolean,
	) {
		fun parseHost(): Host? = if (bracketed) Ipv6Address.parse(hostText) else Ipv4Address.parse(hostText) ?: DomainName.parse(hostText)

		companion object {
			/**
			 * @return null when the brackets are unbalanced, something other than a port follows them,
			 *   or an IPv6 literal is missing them
			 */
			fun parse(hostAndPort: String): Authority? =
				if (hostAndPort.startsWith(Ipv6Address.OPEN_BRACKET)) bracketed(hostAndPort) else unbracketed(hostAndPort)

			private fun bracketed(hostAndPort: String): Authority? {
				if (Ipv6Address.CLOSE_BRACKET !in hostAndPort) return null
				val afterHost = hostAndPort.substringAfter(Ipv6Address.CLOSE_BRACKET)
				if (afterHost.isNotEmpty() && !afterHost.startsWith(ServerUrl.PORT_SEPARATOR)) return null
				return Authority(
					hostText = hostAndPort.substringAfter(Ipv6Address.OPEN_BRACKET).substringBefore(Ipv6Address.CLOSE_BRACKET),
					portText = portTextOf(afterHost),
					bracketed = true,
				)
			}

			private fun unbracketed(hostAndPort: String): Authority? {
				// A second colon means an IPv6 literal without its brackets.
				if (hostAndPort.count { it == ServerUrl.PORT_SEPARATOR } > 1) return null
				return Authority(
					hostText = hostAndPort.substringBefore(ServerUrl.PORT_SEPARATOR),
					portText = portTextOf(hostAndPort),
					bracketed = false,
				)
			}

			/** What follows the port separator in [text], or null when it has none. */
			private fun portTextOf(text: String): String? =
				if (ServerUrl.PORT_SEPARATOR in text) text.substringAfter(ServerUrl.PORT_SEPARATOR) else null
		}
	}

	/** The port an authority names, if any. */
	private sealed interface ParsedPort {
		/** No port separator, or a bare `:` (RFC 3986 allows an empty port). */
		data object Absent : ParsedPort

		data class Present(
			val port: Int,
		) : ParsedPort

		data object Invalid : ParsedPort

		companion object {
			fun parse(portText: String?): ParsedPort =
				when {
					portText.isNullOrEmpty() -> Absent
					!portText.all(Char::isAsciiDigit) -> Invalid
					else -> portText.toIntOrNull()?.takeIf { it in VALID_PORTS }?.let(::Present) ?: Invalid
				}
		}
	}
}

/** [Char.isDigit] also accepts non-ASCII digits, which [String.toIntOrNull] would then read. */
private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
