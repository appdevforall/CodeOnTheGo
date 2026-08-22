package com.itsaky.androidide.utils

/**
 * Turns a stored `ContentTypes.value` into the `Content-Type` a client should be sent.
 *
 * `documentation.db` stores bare MIME types -- no `ContentTypes.value` carries a charset -- so a
 * text response says nothing about its encoding, and a client that does not assume UTF-8 falls back
 * to a legacy single-byte encoding. Two thirds of the database's text rows contain non-ASCII
 * bytes with no BOM, so they render as mojibake wherever that guess goes wrong (ADFA-5241): a
 * page's U+21B3 arrow arriving as the three Latin-1 characters its UTF-8 bytes decode to.
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

	/**
	 * The charset to declare for [mimeType], or null when the type is binary, when it already
	 * carries a charset, or when the format defines its encoding itself.
	 *
	 * `application/json` is deliberately absent: RFC 8259 defines no charset parameter for it and
	 * fixes the encoding as UTF-8, so declaring one is meaningless rather than helpful. XML-based
	 * types are included even though a document may carry its own declaration, because a
	 * transport-level charset takes precedence and SVG in particular usually omits the declaration.
	 */
	fun charsetFor(mimeType: String): String? {
		if (declaresCharset(mimeType)) {
			return null
		}
		val type = mimeType.substringBefore(';').trim().lowercase()
		return when {
			// Every text subtype, plus the database's bare "text" oddity. Matched at the boundary:
			// "textual/example" is not a text type, and startsWith("text") would say it is.
			type == "text" || type.startsWith("text/") -> UTF_8

			type.endsWith("+xml") || type == "application/xml" -> UTF_8

			type == "application/x-typescript" -> UTF_8

			else -> null
		}
	}

	/**
	 * Whether [mimeType] already carries a `charset` *parameter*. Substring-matching "charset="
	 * instead would be fooled by another parameter's value -- `note="charset=utf-8"` -- and would
	 * suppress a declaration the response needs.
	 */
	private fun declaresCharset(mimeType: String): Boolean =
		mimeType
			.split(';')
			.drop(1)
			.any { it.substringBefore('=').trim().equals("charset", ignoreCase = true) }

	/** [mimeType] with a charset appended when [charsetFor] gives one, otherwise unchanged. */
	fun headerValue(mimeType: String): String {
		val charset = charsetFor(mimeType) ?: return mimeType
		return "$mimeType; charset=$charset"
	}
}
