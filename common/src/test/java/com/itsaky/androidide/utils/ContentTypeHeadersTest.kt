package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ADFA-5241: text served without a charset renders as mojibake wherever the client's guess goes
 * wrong. These cover what is actually sent, which is not always what was stored -- the header is
 * rebuilt from the parsed parts so a malformed stored value cannot produce a malformed response.
 */
class ContentTypeHeadersTest {
	@Test
	fun `text types get utf-8`() {
		for (type in listOf("text/html", "text/css", "text/javascript", "text/markdown")) {
			assertThat(ContentTypeHeaders.headerValue(type)).isEqualTo("$type; charset=utf-8")
		}
	}

	// The database stores a bare "text" (one row, x.html) and a "text/text" (ten licence files).
	// Neither is a media type -- type "/" subtype is required -- and a client that cannot parse the
	// type discards the charset with it, which would have made this change a no-op on exactly the
	// rows it was written for.
	@Test
	fun `the database's non-media-type spellings become text-plain`() {
		assertThat(ContentTypeHeaders.headerValue("text")).isEqualTo("text/plain; charset=utf-8")
		assertThat(ContentTypeHeaders.headerValue("text/text")).isEqualTo("text/plain; charset=utf-8")
		assertThat(ContentTypeHeaders.typeAndCharset("text")).isEqualTo("text/plain" to "utf-8")
	}

	// ContentTypes.value comes from a database a debug build will swap in from shared storage, and
	// the header is written with println(). A stored CR/LF would split the response in two.
	@Test
	fun `a type carrying a control character is refused, not repaired`() {
		val injected = "text/html\r\nContent-Length: 0\r\n\r\nHTTP/1.1 200 OK"

		assertThat(ContentTypeHeaders.headerValue(injected)).isEqualTo("application/octet-stream")
		assertThat(ContentTypeHeaders.headerValue("text/html\u0000")).isEqualTo("application/octet-stream")
		assertThat(ContentTypeHeaders.headerValue("")).isEqualTo("application/octet-stream")
	}

	// The type segment being clean is not enough: a parameter is just as much a part of the header
	// line, so a CR/LF there splits the response exactly the same way.
	@Test
	fun `a control character in a parameter is refused too`() {
		val injected = "text/html; note=x\r\nX-Injected: y"

		assertThat(ContentTypeHeaders.headerValue(injected)).isEqualTo("application/octet-stream")
		assertThat(ContentTypeHeaders.typeAndCharset(injected)).isEqualTo("application/octet-stream" to null)
	}

	@Test
	fun `binary types are left alone`() {
		for (type in listOf("image/png", "image/gif", "application/pdf", "font/woff2", "video/mp4")) {
			assertThat(ContentTypeHeaders.charsetFor(type)).isNull()
			assertThat(ContentTypeHeaders.headerValue(type)).isEqualTo(type)
		}
	}

	// RFC 8259 defines no charset parameter for JSON and fixes the encoding as UTF-8, so declaring
	// one says nothing.
	@Test
	fun `json is left alone`() {
		assertThat(ContentTypeHeaders.charsetFor("application/json")).isNull()
		assertThat(ContentTypeHeaders.headerValue("application/json")).isEqualTo("application/json")
	}

	@Test
	fun `xml-based types get utf-8, since svg rarely declares its own`() {
		assertThat(ContentTypeHeaders.headerValue("image/svg+xml")).isEqualTo("image/svg+xml; charset=utf-8")
		assertThat(ContentTypeHeaders.headerValue("application/xml")).isEqualTo("application/xml; charset=utf-8")
	}

	@Test
	fun `an existing charset is kept, never doubled`() {
		assertThat(ContentTypeHeaders.headerValue("text/html; charset=iso-8859-1"))
			.isEqualTo("text/html; charset=iso-8859-1")
		assertThat(ContentTypeHeaders.headerValue("text/html; CHARSET=UTF-8")).isEqualTo("text/html; charset=UTF-8")
	}

	// An unusable charset is replaced rather than contradicted. Appending left the empty one in
	// place, where a first-wins recipient keeps it and ignores what follows -- so the response still
	// had no usable encoding, which is the bug this class exists to remove.
	@Test
	fun `an unusable charset is replaced`() {
		assertThat(ContentTypeHeaders.headerValue("text/html; charset=")).isEqualTo("text/html; charset=utf-8")
		assertThat(ContentTypeHeaders.headerValue("text/html; charset")).isEqualTo("text/html; charset=utf-8")
		assertThat(ContentTypeHeaders.headerValue("text/html; charset=; charset=iso-8859-1"))
			.isEqualTo("text/html; charset=iso-8859-1")
	}

	// Both transports take the same decision from the same call, so a WebView and the socket server
	// cannot declare different encodings for one stored value.
	@Test
	fun `both transports agree on every form of unusable charset`() {
		for (stored in listOf("text/html; charset=", "text/html; charset", "text", "text/html")) {
			val (type, charset) = ContentTypeHeaders.typeAndCharset(stored)
			assertThat(ContentTypeHeaders.headerValue(stored)).isEqualTo("$type; charset=$charset")
		}
	}

	// Substring-matching "charset=" would find it inside another parameter's value and suppress the
	// declaration this response actually needs. The quotes have to survive the rebuild.
	@Test
	fun `charset inside another parameter's value does not count as a declaration`() {
		assertThat(ContentTypeHeaders.headerValue("""text/html; note="charset=utf-8""""))
			.isEqualTo("""text/html; note="charset=utf-8"; charset=utf-8""")
		assertThat(ContentTypeHeaders.headerValue("""text/html; note="x; charset=utf-8""""))
			.isEqualTo("""text/html; note="x; charset=utf-8"; charset=utf-8""")
	}

	// RFC 9110 quoted-pair: an escaped quote does not end the value, so the charset inside note is
	// not a declaration either.
	@Test
	fun `an escaped quote does not end a quoted parameter value`() {
		assertThat(ContentTypeHeaders.typeAndCharset("""text/html; note="a\"; charset=iso-8859-1""""))
			.isEqualTo("text/html" to "utf-8")
	}

	@Test
	fun `a type that merely begins with text is not a text type`() {
		assertThat(ContentTypeHeaders.charsetFor("textual/example")).isNull()
		assertThat(ContentTypeHeaders.headerValue("textual/example")).isEqualTo("textual/example")
	}

	@Test
	fun `casing and stray whitespace on the type are tolerated`() {
		assertThat(ContentTypeHeaders.headerValue("TEXT/HTML")).isEqualTo("TEXT/HTML; charset=utf-8")
		assertThat(ContentTypeHeaders.headerValue("text/html ")).isEqualTo("text/html; charset=utf-8")
		assertThat(ContentTypeHeaders.headerValue("text/html;boundary=x")).isEqualTo("text/html; boundary=x; charset=utf-8")
	}

	@Test
	fun `typeAndCharset splits the type from the charset it should declare`() {
		assertThat(ContentTypeHeaders.typeAndCharset("text/html")).isEqualTo("text/html" to "utf-8")
		assertThat(ContentTypeHeaders.typeAndCharset("image/png")).isEqualTo("image/png" to null)
		assertThat(ContentTypeHeaders.typeAndCharset("text/html; charset=iso-8859-1"))
			.isEqualTo("text/html" to "iso-8859-1")
	}
}
