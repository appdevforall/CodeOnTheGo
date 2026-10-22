package com.itsaky.androidide.plugins.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReservedBlockTest {
	private fun ip(text: String): IpAddress = Ipv4Address.parse(text) ?: requireNotNull(Ipv6Address.parse(text)) { "$text did not parse" }

	@Test
	fun givenEachBlock_whenItsNetworkAddressIsClassified_thenItGetsThatBlocksLocality() {
		// Also proves every entry's CIDR literal parses: a typo would fail the enum's initialization.
		mapOf(
			ReservedBlock.IPV4_UNSPECIFIED to "0.0.0.0",
			ReservedBlock.IPV4_LOOPBACK to "127.0.0.0",
			ReservedBlock.IPV4_PRIVATE_10 to "10.0.0.0",
			ReservedBlock.IPV4_PRIVATE_172 to "172.16.0.0",
			ReservedBlock.IPV4_PRIVATE_192 to "192.168.0.0",
			ReservedBlock.IPV4_LINK_LOCAL to "169.254.0.0",
			ReservedBlock.IPV6_UNSPECIFIED to "::",
			ReservedBlock.IPV6_LOOPBACK to "::1",
			ReservedBlock.IPV6_UNIQUE_LOCAL to "fc00::",
			ReservedBlock.IPV6_LINK_LOCAL to "fe80::",
		).also { assertEquals("a block has no case here", ReservedBlock.entries.toSet(), it.keys) }
			.forEach { (block, address) -> assertEquals(block.name, block.locality, ReservedBlock.localityOf(ip(address))) }
	}

	@Test
	fun givenAPublicAddress_whenClassified_thenNoBlockClaimsIt() {
		listOf("8.8.8.8", "0.0.0.1", "2001:db8::1", "::2").forEach { assertNull(it, ReservedBlock.localityOf(ip(it))) }
	}
}
