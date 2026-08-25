package com.itsaky.androidide.utils

/**
 * Turns a stored `ContentTypes.value` into the `Content-Type` a client should be sent.
 *
 * `documentation.db` stores bare MIME types -- no `ContentTypes.value` carries a charset -- so a
 * text response says nothing about its encoding, and a client that does not assume UTF-8 falls back
 * to a legacy single-byte encoding. 17,903 of the 29,139 text rows in the 21-Aug database --
 * 61.4%, a full census rather than a sample -- contain non-ASCII bytes with no BOM, so they render
 * as mojibake wherever that guess goes wrong (ADFA-5241): a page's U+21B3 arrow arriving as the
 * three Latin-1 characters its UTF-8 bytes decode to. (The rate is per-generation; an older export
 * measures far lower, so quote the database when quoting the number.)
 *
 * This is deliberately *not* fixed by storing the parameter in the database. `ContentTypes.value`
 * doubles as a lookup key matched exactly by the plugin installer
 * (`ExtensionToContentTypeResolver`), by `docdb-studio`'s anchor extraction, and by three scripts in
 * `OfflineDocumentationTools`; two of those would fail silently rather than loudly.
 *
 * Kept in `common` so both documentation transports answer the same way -- the socket server here
 * and ADFA-5176's `shouldInterceptRequest` interceptor, which otherwise carries its own rule.
 */
object ContentTypeHeaders {
	private const val UTF_8 = "utf-8"
	private const val TEXT_PLAIN = "text/plain"

	// What a value carrying a control character becomes: renders nothing, injects nothing.
	private const val OCTET_STREAM = "application/octet-stream"

	// Textual types outside text/* and the +xml family. application/x-typescript has no rows in the
	// current database but costs nothing to keep; application/javascript does have rows, and is what
	// ExtensionToContentTypeResolver maps ".mjs" to, so omitting it served real files undeclared.
	private val TEXTUAL_APPLICATION_TYPES =
		setOf(
			"application/javascript",
			"application/ecmascript",
			"application/x-typescript",
			"application/x-sh",
		)

	/**
	 * The charset to send with [mimeType], or null when there is none to send: the type is binary, or
	 * the format fixes its own encoding.
	 *
	 * `application/json` is deliberately absent: RFC 8259 defines no charset parameter for it and
	 * fixes the encoding as UTF-8, so declaring one is meaningless rather than helpful. XML-based
	 * types are included even though a document may carry its own declaration, because a
	 * transport-level charset takes precedence and SVG in particular usually omits it.
	 */
	internal fun charsetFor(mimeType: String): String? = typeAndCharset(mimeType).second

	/**
	 * The media type and the charset to send with it, which is what
	 * `WebResourceResponse(type, encoding, stream)` wants and what [headerValue] builds its header
	 * from. One decision, so the two documentation transports cannot disagree.
	 *
	 * The type is normalized, not passed through: the database stores a bare `text` and a
	 * `text/text`, neither of which is a media type (`type "/" subtype` is required), and a client
	 * that cannot parse the type discards the charset with it -- which would have made this whole
	 * change a no-op on exactly those rows. A value carrying a control character is refused outright;
	 * see [safeType].
	 *
	 * An unusable `charset=` counts as declaring nothing, so the default applies. [headerValue]
	 * rebuilds the header rather than appending to the stored string, so that substitution reaches
	 * both transports instead of only this one.
	 */
	internal fun typeAndCharset(mimeType: String): Pair<String, String?> {
		val type = safeType(mimeType)
		return type to (declaredCharset(mimeType) ?: defaultCharsetFor(type))
	}

	/**
	 * [mimeType]'s media type, normalized and safe to put in a header.
	 *
	 * A control character makes the whole value untrustworthy: `ContentTypes.value` comes from a
	 * database that a debug build will swap in from shared storage (`WebServer`'s
	 * `debugDatabasePath`), and a stored `text/html\r\n\r\n...` would otherwise be written
	 * straight into the response by `println`, splitting it into two. Such a value is not repaired,
	 * it is refused: `application/octet-stream` renders nothing and injects nothing.
	 */
	private fun safeType(mimeType: String): String {
		// The *whole* stored value, not just the segment before the first ';'. A control character in
		// a parameter -- text/html; note=x<CR><LF>X-Injected: y -- would otherwise pass this check and
		// then be written into the header by the parameter loop in headerValue, which is the same
		// response splitting, one segment further along.
		if (mimeType.any { it.isISOControl() }) {
			return OCTET_STREAM
		}
		val type = mimeType.substringBefore(';').trim()
		if (type.isEmpty()) {
			return OCTET_STREAM
		}
		// "text" and "text/text" are the database's own spellings for plain text, and neither parses
		// as a media type.
		return if (type.equals("text", ignoreCase = true) || type.equals("text/text", ignoreCase = true)) {
			TEXT_PLAIN
		} else {
			type
		}
	}

	private fun defaultCharsetFor(safeType: String): String? {
		val type = safeType.lowercase()
		return when {
			// Matched at the boundary: "textual/example" is not a text type, and startsWith("text")
			// would say it is.
			type.startsWith("text/") -> UTF_8

			type.endsWith("+xml") || type == "application/xml" -> UTF_8

			// Textual application/* types share no syntactic marker, hence a list. application/json
			// is deliberately absent (see the class KDoc). Anything textual that turns up later and
			// is not here serves undeclared -- the bug this class exists to prevent -- so add it
			// rather than assuming the list is complete.
			type in TEXTUAL_APPLICATION_TYPES -> UTF_8

			else -> null
		}
	}

	/**
	 * The charset [mimeType] already declares, or null when it declares none that is usable.
	 *
	 * Parsed rather than substring-matched: "charset=" occurs inside other parameters' values
	 * (`note="charset=utf-8"`), and splitting naively on ';' still finds it when the value itself
	 * contains a semicolon (`note="x; charset=utf-8"`). A parameter with no value (`; charset`) or
	 * an empty one (`; charset=`) declares nothing and must not suppress the default -- treating it
	 * as a declaration is how a response ends up with no encoding at all.
	 *
	 * The first *usable* one, not simply the first. While this class appended to the stored string,
	 * reading the first mattered -- that is the one a recipient keeps when a name repeats, so
	 * honouring a later one would have meant acting on a parameter the client ignores. [headerValue]
	 * rebuilds the header now and emits exactly one charset, so that no longer applies, and reading
	 * past an unusable parameter is what keeps `charset=; charset=iso-8859-1` from being served as
	 * utf-8 -- which would garble a page that says plainly what it is.
	 */
	private fun declaredCharset(mimeType: String): String? =
		parameters(mimeType)
			.firstOrNull { (name, value) -> name.equals("charset", ignoreCase = true) && !value.isNullOrEmpty() }
			?.second

	/**
	 * [mimeType]'s `name=value` parameters, with semicolons inside quoted values left alone. A null
	 * value means the parameter carried no `=` at all, which recipients drop entirely -- see
	 * [carriesCharsetParameter].
	 */
	private fun parameters(mimeType: String): List<Pair<String, String?>> {
		val found = mutableListOf<Pair<String, String?>>()
		val token = StringBuilder()
		var quoted = false

		// RFC 9110 quoted-pair: inside a quoted string a backslash escapes the next character, so
		// \" does not end the value. Without this, text/html; note="a\"; charset=iso-8859-1 parses
		// as two parameters and the charset inside note reads as a declaration.
		var escaped = false

		fun take() {
			val text = token.toString().trim()
			token.setLength(0)
			if (text.isEmpty()) return
			val name = text.substringBefore('=').trim()
			val value = if (text.contains('=')) text.substringAfter('=').trim().trim('"') else null
			found += name to value
		}

		var index = mimeType.indexOf(';')
		if (index < 0) return found
		while (++index < mimeType.length) {
			val character = mimeType[index]
			when {
				escaped -> {
					escaped = false
					token.append(character)
				}

				quoted && character == '\\' -> {
					escaped = true
					token.append(character)
				}

				character == '"' -> {
					quoted = !quoted
					token.append(character)
				}

				character == ';' && !quoted -> {
					take()
				}

				else -> {
					token.append(character)
				}
			}
		}
		take()
		return found
	}

	/**
	 * The `Content-Type` header value for a row stored as [mimeType].
	 *
	 * Rebuilt from the parsed parts rather than appended to. Appending produced a header no stricter
	 * than what the database happened to hold: a bare `text` stayed unparseable, an unusable
	 * `charset=` stayed and contradicted the one added after it, and a valueless `charset` was left
	 * beside its replacement. Rebuilding emits one normalized type, the other parameters as they
	 * were, and exactly one charset -- the same one [typeAndCharset] hands the other transport.
	 */
	fun headerValue(mimeType: String): String {
		val (type, charset) = typeAndCharset(mimeType)
		// Nothing from a refused value is re-emitted: its parameters are exactly where the control
		// characters would have been.
		if (type == OCTET_STREAM && charset == null) {
			return OCTET_STREAM
		}
		return buildString {
			append(type)
			for ((name, value) in parameters(mimeType)) {
				if (name.equals("charset", ignoreCase = true)) continue
				append("; ").append(name)
				if (value != null) append('=').append(quoteIfNeeded(value))
			}
			if (charset != null) append("; charset=").append(charset)
		}
	}

	/**
	 * Re-quotes a parameter value that needed quoting in the first place. [parameters] strips the
	 * quotes it parsed, so a value containing a space or a separator has to get them back or the
	 * rebuilt header means something different from the stored one.
	 */
	private fun quoteIfNeeded(value: String): String =
		if (value.isNotEmpty() && value.none { it.isWhitespace() || it in "\"(),/:;<=>?@[\\]{}" }) {
			value
		} else {
			"\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
		}
}
