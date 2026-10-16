package com.itsaky.androidide.plugins.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv6AddressTest {
	private fun localityOf(text: String): NetworkLocality = requireNotNull(Ipv6Address.parse(text)) { "$text did not parse" }.locality

	private fun assertLocality(
		expected: NetworkLocality,
		vararg addresses: String,
	) = addresses.forEach { assertEquals(it, expected, localityOf(it)) }

	@Test
	fun givenLoopbackInEveryForm_whenClassified_thenItIsLoopback() {
		assertLocality(NetworkLocality.LOOPBACK, "::1", "::", "::ffff:0.0.0.0", "0:0:0:0:0:0:0:1", "0::1", "::0:1", "::ffff:127.0.0.1")
	}

	@Test
	fun givenPrivateRanges_whenClassified_thenTheyArePrivate() {
		assertLocality(
			NetworkLocality.PRIVATE_NETWORK,
			"fc00::1",
			"fdff:ffff::1",
			"fd12:3456:789a::1",
			"fe80::1",
			"febf::1",
			"FE80::1",
			"::ffff:192.168.1.5",
		)
	}

	@Test
	fun givenAddressesJustOutsideThePrivateRanges_whenClassified_thenTheyArePublic() {
		assertLocality(NetworkLocality.PUBLIC, "fbff::1", "fec0::1", "2001:db8::1", "::2", "::ffff:8.8.8.8")
	}

	@Test
	fun givenAZoneId_whenParsed_thenItIsDropped() {
		val address = requireNotNull(Ipv6Address.parse("fe80::1%wlan0"))
		assertEquals("fe80::1", address.text)
		assertEquals(NetworkLocality.PRIVATE_NETWORK, address.locality)
	}

	@Test
	fun givenMalformedLiterals_whenParsed_thenTheyAreNotAddresses() {
		listOf(
			"",
			"localhost",
			"1::2::3",
			":::",
			":1:2:3:4:5:6:7",
			"1:2:3:4:5:6:7:",
			"12345::1",
			"1:2:3:4:5:6:7",
			"1:2:3:4:5:6:7:8:9",
			"1:2:3:4:5:6:7::8",
			"::ffff:999.0.0.1",
			"::1.2.3.4:5",
			"g::1",
		).forEach { assertNull(it, Ipv6Address.parse(it)) }
	}
}
