package com.itsaky.androidide.plugins.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4AddressTest {
	private fun localityOf(text: String): NetworkLocality = requireNotNull(Ipv4Address.parse(text)) { "$text did not parse" }.locality

	private fun assertLocality(
		expected: NetworkLocality,
		vararg addresses: String,
	) = addresses.forEach { assertEquals(it, expected, localityOf(it)) }

	@Test
	fun givenLoopbackAddresses_whenClassified_thenTheyAreLoopback() {
		assertLocality(NetworkLocality.LOOPBACK, "0.0.0.0", "127.0.0.0", "127.0.0.1", "127.1.2.3", "127.255.255.255")
	}

	@Test
	fun givenEveryPrivateRange_whenClassified_thenEachIsPrivate() {
		assertLocality(
			NetworkLocality.PRIVATE_NETWORK,
			"10.0.0.0",
			"10.255.255.255",
			"10.0.2.2",
			"172.16.0.0",
			"172.31.255.255",
			"192.168.0.0",
			"192.168.255.255",
			"169.254.0.0",
			"169.254.255.255",
		)
	}

	@Test
	fun givenAddressesJustOutsideEachRange_whenClassified_thenTheyArePublic() {
		assertLocality(
			NetworkLocality.PUBLIC,
			"126.255.255.255",
			"128.0.0.0",
			"9.255.255.255",
			"11.0.0.0",
			"172.15.255.255",
			"172.32.0.0",
			"192.167.255.255",
			"192.169.0.0",
			"169.253.255.255",
			"169.255.0.0",
			"8.8.8.8",
			"0.0.0.1",
		)
	}

	@Test
	fun givenTextThatIsNotADottedQuad_whenParsed_thenItIsNotAnAddress() {
		listOf("256.0.0.1", "1.2.3", "1.2.3.4.5", "1.2.3.", "a.b.c.d", "1234.0.0.1", "127.1").forEach {
			assertNull(it, Ipv4Address.parse(it))
		}
	}

	@Test
	fun givenLeadingZeros_whenParsed_thenItIsNotAnAddress() {
		// Some resolvers read 010 as octal 8, so 010.0.0.1 must not pass as 10/8.
		listOf("010.0.0.1", "127.00.0.1", "192.168.001.1").forEach { assertNull(it, Ipv4Address.parse(it)) }
	}

	@Test
	fun givenAnAddress_whenParsed_thenItsBytesAreBigEndian() {
		assertEquals(listOf(192, 168, 1, 5), requireNotNull(Ipv4Address.parse("192.168.1.5")).bytes)
	}

	@Test
	fun givenItsBytes_whenRebuilt_thenTheAddressIsUnchanged() {
		assertEquals("192.168.1.5", Ipv4Address.fromBytes(listOf(192, 168, 1, 5)).text)
	}

	@Test(expected = IllegalArgumentException::class)
	fun givenTheWrongNumberOfBytes_whenRebuilt_thenItIsRefused() {
		Ipv4Address.fromBytes(listOf(1, 2, 3))
	}

	@Test(expected = IllegalArgumentException::class)
	fun givenAByteOutOfRange_whenRebuilt_thenItIsRefused() {
		Ipv4Address.fromBytes(listOf(1, 2, 3, 256))
	}
}
