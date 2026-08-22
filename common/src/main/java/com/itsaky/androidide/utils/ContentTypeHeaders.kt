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
	 * The charset to declare for [mimeType], or null when the type is binary, when it already
	 * carries a charset, or when the format defines its encoding itself.
	 *
	 * `application/json` is deliberately absent: RFC 8259 defines no charset parameter for it and
	 * fixes the encoding as UTF-8, so declaring one is meaningless rather than helpful. XML-based
	 * types are included even though a document may carry its own declaration, because a
	 * transport-level charset takes precedence and SVG in particular usually omits the declaration.
	 */
	fun charsetFor(mimeType: String): String? = if (carriesCharsetParameter(mimeType)) null else defaultCharsetFor(mimeType)

	/**
	 * The bare media type and the charset to send with it: whatever [mimeType] already declares,
	 * otherwise this class's default for that type, otherwise null.
	 *
	 * Exists because `WebResourceResponse(type, encoding, stream)` wants the two apart, and the
	 * in-process transport was re-implementing the parse to get them -- with the naive substring
	 * match this file warns against below. One parse, both transports.
	 */
	fun typeAndCharset(mimeType: String): Pair<String, String?> {
		val type = mimeType.substringBefore(';').trim()
		return type to (declaredCharset(mimeType) ?: defaultCharsetFor(mimeType))
	}

	private fun defaultCharsetFor(mimeType: String): String? {
		val type = mimeType.substringBefore(';').trim().lowercase()
		return when {
			// Every text subtype, plus the database's bare "text" oddity. Matched at the boundary:
			// "textual/example" is not a text type, and startsWith("text") would say it is.
			type == "text" || type.startsWith("text/") -> UTF_8

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
	 */
	private fun declaredCharset(mimeType: String): String? = firstCharsetParameter(mimeType)?.second?.ifEmpty { null }

	/**
	 * Whether [mimeType] already carries a `charset` parameter *with an `=`*, usable or not -- the
	 * question [headerValue] has to ask, which is not the same as whether the charset is usable.
	 *
	 * `; charset=` (empty) and `; charset` (no value at all) are both useless as declarations, but
	 * recipients treat them differently. A parameter with no `=` is dropped during parsing, so an
	 * appended `; charset=utf-8` becomes the only one and takes effect. An empty *valued* parameter
	 * is kept, and a repeated parameter name is ignored, so appending a second one gets us a header
	 * carrying two conflicting charsets and, in a first-wins recipient, no change in behaviour. Not
	 * worth emitting: the empty parameter is a defect in the stored `ContentTypes.value` and belongs
	 * fixed there. [typeAndCharset] can and does still substitute the default, because it hands the
	 * charset back as its own value where nothing can conflict with it.
	 */
	private fun carriesCharsetParameter(mimeType: String): Boolean = firstCharsetParameter(mimeType)?.second != null

	/**
	 * [mimeType]'s first `charset` parameter, or null when it has none. First, not first-usable:
	 * that is the one a recipient keeps when a name repeats, so reading any other would honour a
	 * parameter the client ignores.
	 */
	private fun firstCharsetParameter(mimeType: String): Pair<String, String?>? =
		parameters(mimeType).firstOrNull { (name, _) -> name.equals("charset", ignoreCase = true) }

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

	/** [mimeType] with a charset appended when [charsetFor] gives one, otherwise unchanged. */
	fun headerValue(mimeType: String): String {
		val charset = charsetFor(mimeType) ?: return mimeType
		return "$mimeType; charset=$charset"
	}
}
