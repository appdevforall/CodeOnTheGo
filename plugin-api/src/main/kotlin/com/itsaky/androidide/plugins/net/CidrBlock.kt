package com.itsaky.androidide.plugins.net

/** A network prefix such as `10.0.0.0/8`: the addresses whose first [prefixLength] bits match [network]. */
internal data class CidrBlock(
	private val network: List<Int>,
	private val prefixLength: Int,
) {
	init {
		require(prefixLength in 0..network.size * Byte.SIZE_BITS) { "prefix /$prefixLength does not fit ${network.size} bytes" }
	}

	/** False for an address of the other family, whose bytes cannot share a prefix with this one. */
	operator fun contains(address: IpAddress): Boolean {
		val bytes = address.bytes
		if (bytes.size != network.size) return false

		val wholeBytes = prefixLength / Byte.SIZE_BITS
		if (bytes.subList(0, wholeBytes) != network.subList(0, wholeBytes)) return false

		val spareBits = prefixLength % Byte.SIZE_BITS
		if (spareBits == 0) return true
		val mask = (BYTE_MASK shl (Byte.SIZE_BITS - spareBits)) and BYTE_MASK
		return bytes[wholeBytes] and mask == network[wholeBytes] and mask
	}

	companion object {
		private const val PREFIX_SEPARATOR = '/'

		/**
		 * @param notation `address/prefixLength`, IPv4 or IPv6
		 * @throws IllegalArgumentException when [notation] is not CIDR notation
		 */
		fun parse(notation: String): CidrBlock {
			val addressText = notation.substringBefore(PREFIX_SEPARATOR)
			val address: IpAddress =
				Ipv4Address.parse(addressText) ?: Ipv6Address.parse(addressText)
					?: throw IllegalArgumentException("not an IP address: $addressText")
			val prefixLength =
				notation.substringAfter(PREFIX_SEPARATOR, missingDelimiterValue = "").toIntOrNull()
					?: throw IllegalArgumentException("no prefix length: $notation")
			return CidrBlock(address.bytes, prefixLength)
		}
	}
}
