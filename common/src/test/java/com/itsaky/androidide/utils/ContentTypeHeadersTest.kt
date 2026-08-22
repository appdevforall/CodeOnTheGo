package com.itsaky.androidide.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ADFA-5241: every text response has to declare its encoding, and no binary response may. */
class ContentTypeHeadersTest {
	@Test
	fun `text types get utf-8`() {
		for (type in listOf("text/html", "text/css", "text/javascript", "text/markdown", "text/plain")) {
			assertEquals("$type; charset=utf-8", ContentTypeHeaders.headerValue(type))
		}
	}

	// documentation.db really contains these two: a bare "text" (which 726 TooltipButtons point at,
	// via x.html) and a "text/text". Neither is a valid MIME type, but both are text.
	@Test
	fun `the database's malformed text types are still treated as text`() {
		assertEquals("text; charset=utf-8", ContentTypeHeaders.headerValue("text"))
		assertEquals("text/text; charset=utf-8", ContentTypeHeaders.headerValue("text/text"))
	}

	@Test
	fun `xml-based types get utf-8, since svg rarely declares its own`() {
		assertEquals("image/svg+xml; charset=utf-8", ContentTypeHeaders.headerValue("image/svg+xml"))
		assertEquals("application/xml; charset=utf-8", ContentTypeHeaders.headerValue("application/xml"))
	}

	@Test
	fun `binary types are left alone`() {
		for (type in listOf(
			"image/png",
			"image/gif",
			"image/jpeg",
			"image/webp",
			"image/x-icon",
			"video/mp4",
			"video/quicktime",
			"application/pdf",
			"application/wasm",
			"font/woff2",
			"font/ttf",
			"application/octet-stream",
			"application/vnd-iccprofile",
		)) {
			assertNull(ContentTypeHeaders.charsetFor(type))
			assertEquals(type, ContentTypeHeaders.headerValue(type))
		}
	}

	// RFC 8259 defines no charset parameter for JSON and fixes the encoding as UTF-8, so declaring
	// one says nothing. Asserted so nobody "fixes" this by adding it.
	@Test
	fun `json is left alone`() {
		assertNull(ContentTypeHeaders.charsetFor("application/json"))
		assertEquals("application/json", ContentTypeHeaders.headerValue("application/json"))
	}

	@Test
	fun `an existing charset is never doubled`() {
		assertEquals("text/html; charset=utf-8", ContentTypeHeaders.headerValue("text/html; charset=utf-8"))
		assertEquals("text/html; charset=iso-8859-1", ContentTypeHeaders.headerValue("text/html; charset=iso-8859-1"))
		assertEquals("text/html; CHARSET=UTF-8", ContentTypeHeaders.headerValue("text/html; CHARSET=UTF-8"))
	}

	// startsWith("text") would call this a text type. The intent is "text" or "text/", nothing else.
	// Splitting on ';' alone still finds "charset=" inside a quoted value that contains a semicolon,
	// and then suppresses the declaration the response actually needs.
	@Test
	fun `a semicolon inside a quoted parameter value does not hide the charset`() {
		assertEquals(
			"""text/html; note="x; charset=utf-8"; charset=utf-8""",
			ContentTypeHeaders.headerValue("""text/html; note="x; charset=utf-8""""),
		)
	}

	// A parameter with no value declares nothing and is dropped during parsing, so appending the
	// default works and is what the response needs.
	@Test
	fun `a valueless charset parameter is not a declaration`() {
		assertEquals("text/html; charset; charset=utf-8", ContentTypeHeaders.headerValue("text/html; charset"))
	}

	// An empty *valued* parameter is kept by recipients, and a repeated name is ignored, so a second
	// charset would conflict with it and change nothing. Emitting one claims a fix it does not make;
	// the empty parameter is a defect in the stored value and belongs fixed there.
	@Test
	fun `an empty charset parameter is left alone rather than contradicted`() {
		assertEquals("text/html; charset=", ContentTypeHeaders.headerValue("text/html; charset="))
		assertEquals(
			"text/html; charset=; charset=iso-8859-1",
			ContentTypeHeaders.headerValue("text/html; charset=; charset=iso-8859-1"),
		)
	}

	// RFC 9110 quoted-pair. Toggling on every quote ends the value at the escaped one, and then the
	// charset inside note parses as a declaration -- the same false match this class exists to stop.
	@Test
	fun `an escaped quote does not end a quoted parameter value`() {
		assertEquals(
			"""text/html; note="a\"; charset=iso-8859-1"; charset=utf-8""",
			ContentTypeHeaders.headerValue("""text/html; note="a\"; charset=iso-8859-1""""),
		)
		assertEquals(
			"text/html" to "utf-8",
			ContentTypeHeaders.typeAndCharset("""text/html; note="a\"; charset=iso-8859-1""""),
		)
	}

	// Textual application/* types have no syntactic marker in common. application/javascript is the
	// one with rows in the database and is what ".mjs" resolves to.
	@Test
	fun `textual application types get utf-8 and json still does not`() {
		assertEquals("application/javascript; charset=utf-8", ContentTypeHeaders.headerValue("application/javascript"))
		assertEquals("application/ecmascript; charset=utf-8", ContentTypeHeaders.headerValue("application/ecmascript"))
		assertNull(ContentTypeHeaders.charsetFor("application/json"))
	}

	// The other transport needs the two apart for WebResourceResponse, and must not re-parse.
	@Test
	fun `typeAndCharset splits the type from the charset it should declare`() {
		assertEquals("text/html" to "utf-8", ContentTypeHeaders.typeAndCharset("text/html"))
		assertEquals("image/png" to null, ContentTypeHeaders.typeAndCharset("image/png"))
		assertEquals("text/html" to "iso-8859-1", ContentTypeHeaders.typeAndCharset("text/html; charset=iso-8859-1"))
		assertEquals("text/html" to "UTF-8", ContentTypeHeaders.typeAndCharset("text/html; CHARSET=UTF-8"))
		// a declared-but-empty parameter falls back to the default rather than to nothing
		assertEquals("text/html" to "utf-8", ContentTypeHeaders.typeAndCharset("text/html; charset="))
	}

	@Test
	fun `a type that merely begins with text is not a text type`() {
		assertNull(ContentTypeHeaders.charsetFor("textual/example"))
		assertEquals("textual/example", ContentTypeHeaders.headerValue("textual/example"))
	}

	// Substring-matching "charset=" would find it inside another parameter's value and suppress the
	// declaration this response actually needs.
	@Test
	fun `charset inside another parameter's value does not count as a declaration`() {
		assertEquals(
			"""text/html; note="charset=utf-8"; charset=utf-8""",
			ContentTypeHeaders.headerValue("""text/html; note="charset=utf-8""""),
		)
	}

	@Test
	fun `parameters and casing on the type itself are tolerated`() {
		assertEquals("TEXT/HTML; charset=utf-8", ContentTypeHeaders.headerValue("TEXT/HTML"))
		assertEquals("text/html ; charset=utf-8", ContentTypeHeaders.headerValue("text/html "))
		assertEquals("text/html;boundary=x; charset=utf-8", ContentTypeHeaders.headerValue("text/html;boundary=x"))
	}
}
