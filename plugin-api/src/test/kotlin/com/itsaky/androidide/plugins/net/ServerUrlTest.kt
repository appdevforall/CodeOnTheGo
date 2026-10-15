package com.itsaky.androidide.plugins.net

import com.itsaky.androidide.plugins.net.ServerUrlParseResult.Invalid.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
	private fun valid(input: String): ServerUrl {
		val result = ServerUrl.parse(input)
		check(result is ServerUrlParseResult.Valid) { "expected $input to parse, got $result" }
		return result.url
	}

	private fun reasonFor(input: String?): Reason {
		val result = ServerUrl.parse(input)
		check(result is ServerUrlParseResult.Invalid) { "expected $input to be refused, got $result" }
		return result.reason
	}

	@Test
	fun givenOneUrlPerHostKind_whenParsed_thenEachGetsItsLocality() {
		mapOf(
			"http://localhost:3000/mcp" to NetworkLocality.LOOPBACK,
			"http://127.0.0.1" to NetworkLocality.LOOPBACK,
			"http://[::1]:8080/mcp" to NetworkLocality.LOOPBACK,
			"http://0.0.0.0:8080" to NetworkLocality.LOOPBACK,
			"http://localhost.:3000" to NetworkLocality.LOOPBACK,
			"http://192.168.1.50:11434" to NetworkLocality.PRIVATE_NETWORK,
			"http://[fe80::1%25wlan0]" to NetworkLocality.PRIVATE_NETWORK,
			"http://raspberrypi" to NetworkLocality.PRIVATE_NETWORK,
			"https://api.openai.com/v1" to NetworkLocality.PUBLIC,
			"http://8.8.8.8:53" to NetworkLocality.PUBLIC,
		).forEach { (url, locality) -> assertEquals(url, locality, valid(url).locality) }
	}

	@Test
	fun givenAFullUrl_whenParsed_thenSchemeHostAndPortAreRead() {
		val url = valid("http://user:secret@127.0.0.1:8080/mcp/sse?x=1#top")
		assertEquals(ServerUrl.Scheme.HTTP, url.scheme)
		assertEquals("127.0.0.1", url.host)
		assertEquals(8080, url.port)
	}

	@Test
	fun givenUserInfoNamingAnotherHost_whenParsed_thenTheRealHostDecides() {
		assertEquals(NetworkLocality.PUBLIC, valid("http://localhost@evil.example.com/").locality)
		assertEquals(NetworkLocality.LOOPBACK, valid("http://evil.example.com@localhost/").locality)
	}

	@Test
	fun givenUppercaseSchemeAndHost_whenParsed_thenTheyAreNormalized() {
		val url = valid("HTTP://LocalHost:80")
		assertEquals(ServerUrl.Scheme.HTTP, url.scheme)
		assertEquals("localhost", url.host)
		assertEquals(ServerUrl.Scheme.HTTPS, valid("HTTPS://Example.COM").scheme)
	}

	@Test
	fun givenHttpAndHttps_whenParsed_thenOnlyHttpIsCleartext() {
		assertTrue(valid("http://example.com").isCleartext)
		assertFalse(valid("https://example.com").isCleartext)
	}

	@Test
	fun givenEachScheme_whenReadingItsUrlFormAndEncryption_thenTheyMatchTheProtocol() {
		assertEquals("http", ServerUrl.Scheme.HTTP.id)
		assertFalse(ServerUrl.Scheme.HTTP.isEncrypted)
		assertEquals("https", ServerUrl.Scheme.HTTPS.id)
		assertTrue(ServerUrl.Scheme.HTTPS.isEncrypted)
	}

	@Test
	fun givenNoPort_whenParsed_thenThePortIsNull() {
		assertNull(valid("https://example.com/v1").port)
		assertNull(valid("http://localhost:/v1").port)
		assertNull(valid("http://[::1]").port)
	}

	@Test
	fun givenSurroundingWhitespace_whenParsed_thenItIsIgnored() {
		assertEquals("localhost", valid("  http://localhost:8080  ").host)
	}

	@Test
	fun givenTheSameServerWrittenTwoWays_whenCompared_thenTheyAreEqual() {
		assertEquals(valid("http://LOCALHOST:80/a"), valid("http://localhost:80/b?q"))
		assertEquals(valid("http://LOCALHOST:80/a").hashCode(), valid("http://localhost:80/b?q").hashCode())
		assertNotEquals(valid("http://localhost:80"), valid("https://localhost:80"))
		assertNotEquals(valid("http://localhost"), valid("http://localhost:80"))
	}

	@Test
	fun givenAUrl_whenPrinted_thenItIsTheCanonicalOrigin() {
		assertEquals("http://localhost:8080", valid("HTTP://LocalHost:8080/mcp").toString())
		assertEquals("https://[::1]", valid("https://[::1]/").toString())
	}

	@Test
	fun givenBlankInput_whenParsed_thenItIsRefusedAsBlank() {
		listOf(null, "", "   ").forEach { assertEquals(Reason.BLANK, reasonFor(it)) }
	}

	@Test
	fun givenOtherSchemes_whenParsed_thenTheyAreUnsupported() {
		listOf("ftp://example.com", "ws://localhost:8080", "file:///sdcard/x").forEach {
			assertEquals(it, Reason.UNSUPPORTED_SCHEME, reasonFor(it))
		}
	}

	@Test
	fun givenMalformedUrls_whenParsed_thenTheyAreRefusedAsMalformed() {
		listOf(
			"localhost:8080",
			"example.com/v1",
			"http:/example.com",
			"http://exa mple.com",
			"http://example.com:99999",
			"http://example.com:65536",
			"http://example.com:80a",
			"http://example.com:-1",
			"http://ex%41mple.com",
			"http://::1/",
			"http://[::1",
			"http://[::1]x",
			"http://[1::2::3]",
			"http://[localhost]",
			"http://[127.0.0.1]",
		).forEach { assertEquals(it, Reason.MALFORMED, reasonFor(it)) }
	}

	@Test
	fun givenTheHighestPort_whenParsed_thenItIsAccepted() {
		assertEquals(65535, valid("http://example.com:65535").port)
	}

	@Test
	fun givenNoHost_whenParsed_thenItIsRefusedAsNoHost() {
		listOf("http://", "https:///v1", "http://user@/v1", "http://:8080", "http://[]").forEach {
			assertEquals(it, Reason.NO_HOST, reasonFor(it))
		}
	}
}
