package com.itsaky.androidide.plugins.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainNameTest {
	private fun localityOf(text: String): NetworkLocality = requireNotNull(DomainName.parse(text)) { "$text did not parse" }.locality

	private fun assertLocality(
		expected: NetworkLocality,
		vararg names: String,
	) = names.forEach { assertEquals(it, expected, localityOf(it)) }

	@Test
	fun givenLocalhost_whenClassified_thenItIsLoopback() {
		assertLocality(NetworkLocality.LOOPBACK, "localhost", "LocalHost", "localhost.")
	}

	@Test
	fun givenLanNames_whenClassified_thenTheyArePrivate() {
		assertLocality(NetworkLocality.PRIVATE_NETWORK, "raspberrypi", "my_server", "printer.local", "MyMac.LOCAL", "printer.local.")
	}

	@Test
	fun givenNamesThatOnlyLookLocal_whenClassified_thenTheyArePublic() {
		// Names are never resolved, so only the exact forms above count as local.
		assertLocality(
			NetworkLocality.PUBLIC,
			"localhost.example.com",
			"127.0.0.1.example.com",
			"local.example.com",
			"fdroid.org",
			"fe80.example.com",
			"999.1.1.1",
			"010.0.0.1",
			// Absolute, so the LAN's search domain is never appended.
			"raspberrypi.",
		)
	}

	@Test
	fun givenAName_whenParsed_thenItIsLowercased() {
		assertEquals("api.openai.com", DomainName.parse("API.OpenAI.com")?.text)
	}

	@Test
	fun givenCharactersNoHostNameUses_whenParsed_thenItIsNotAName() {
		listOf("", "ex%41mple.com", "exa mple.com", "a:b", "[::1]", "a/b").forEach { assertNull(it, DomainName.parse(it)) }
	}
}
