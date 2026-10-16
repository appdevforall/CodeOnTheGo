package com.itsaky.androidide.plugins.net

/** Not a data class: `copy` would let callers skip [parse]'s validation. */
internal class Ipv4Address private constructor(
	override val bytes: List<Int>,
) : IpAddress {
	override val text: String get() = bytes.joinToString(OCTET_SEPARATOR.toString())

	companion object {
		const val BYTE_COUNT = Int.SIZE_BYTES

		private const val OCTET_SEPARATOR = '.'

		/**
		 * A decimal octet without leading zeros: some resolvers read `010` as octal 8, so calling
		 * `010.0.0.1` 10/8 could wave cleartext through to a public host. It falls through to a name.
		 */
		private val DECIMAL_OCTET = Regex("""0|[1-9]\d{0,2}""")

		fun parse(text: String): Ipv4Address? {
			val parts = text.split(OCTET_SEPARATOR)
			if (parts.size != BYTE_COUNT || !parts.all(DECIMAL_OCTET::matches)) return null
			val octets = parts.map(String::toInt)
			return if (octets.all { it <= BYTE_MASK }) Ipv4Address(octets) else null
		}

		/** The address carried in the last [BYTE_COUNT] bytes of an IPv4-mapped IPv6 address. */
		fun fromBytes(bytes: List<Int>): Ipv4Address {
			require(bytes.size == BYTE_COUNT && bytes.all { it in 0..BYTE_MASK }) { "not an IPv4 address: $bytes" }
			// Copied: a caller's MutableList could change after the check above.
			return Ipv4Address(bytes.toList())
		}
	}
}
