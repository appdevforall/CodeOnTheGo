package com.itsaky.androidide.plugins.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CidrBlockTest {
	private fun ip(text: String): IpAddress = Ipv4Address.parse(text) ?: requireNotNull(Ipv6Address.parse(text)) { "$text did not parse" }

	@Test
	fun givenAByteAlignedPrefix_whenMatching_thenOnlyThatPrefixMatches() {
		val block = CidrBlock.parse("192.168.0.0/16")
		assertTrue(ip("192.168.0.0") in block)
		assertTrue(ip("192.168.255.255") in block)
		assertFalse(ip("192.167.255.255") in block)
		assertFalse(ip("192.169.0.0") in block)
	}

	@Test
	fun givenAPrefixEndingMidByte_whenMatching_thenTheBoundaryBitsDecide() {
		// /12 ends four bits into the second byte: 172.16-172.31.
		val block = CidrBlock.parse("172.16.0.0/12")
		assertTrue(ip("172.16.0.0") in block)
		assertTrue(ip("172.31.255.255") in block)
		assertFalse(ip("172.15.255.255") in block)
		assertFalse(ip("172.32.0.0") in block)
	}

	@Test
	fun givenIpv6PrefixesEndingMidByte_whenMatching_thenTheBoundaryBitsDecide() {
		val uniqueLocal = CidrBlock.parse("fc00::/7")
		assertTrue(ip("fc00::") in uniqueLocal)
		assertTrue(ip("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff") in uniqueLocal)
		assertFalse(ip("fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff") in uniqueLocal)
		assertFalse(ip("fe00::") in uniqueLocal)

		val linkLocal = CidrBlock.parse("fe80::/10")
		assertTrue(ip("febf::1") in linkLocal)
		assertFalse(ip("fec0::1") in linkLocal)
	}

	@Test
	fun givenAFullLengthPrefix_whenMatching_thenOnlyThatAddressMatches() {
		val block = CidrBlock.parse("::1/128")
		assertTrue(ip("::1") in block)
		assertFalse(ip("::2") in block)
	}

	@Test
	fun givenAZeroLengthPrefix_whenMatching_thenEveryAddressOfThatFamilyMatches() {
		val block = CidrBlock.parse("0.0.0.0/0")
		assertTrue(ip("8.8.8.8") in block)
		assertTrue(ip("255.255.255.255") in block)
	}

	@Test
	fun givenTheOtherFamily_whenMatching_thenItNeverMatches() {
		assertFalse(ip("::") in CidrBlock.parse("0.0.0.0/0"))
		assertFalse(ip("0.0.0.0") in CidrBlock.parse("::/0"))
	}

	@Test
	fun givenTheSameNotation_whenParsedTwice_thenTheBlocksAreEqual() {
		assertEquals(CidrBlock.parse("10.0.0.0/8"), CidrBlock.parse("10.0.0.0/8"))
	}

	@Test
	fun givenBadNotation_whenParsed_thenItIsRefused() {
		listOf("10.0.0.0", "10.0.0.0/", "10.0.0.0/x", "10.0.0.0/33", "::/129", "nonsense/8", "10.0.0.0/-1").forEach {
			val refused = runCatching { CidrBlock.parse(it) }.exceptionOrNull()
			assertTrue("$it was accepted", refused is IllegalArgumentException)
		}
	}
}
