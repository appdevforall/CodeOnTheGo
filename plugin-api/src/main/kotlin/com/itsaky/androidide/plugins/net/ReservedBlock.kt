package com.itsaky.androidide.plugins.net

/**
 * The address blocks that are not the public internet. An address in none of them is
 * [NetworkLocality.PUBLIC]; supporting another range is one more entry here.
 */
internal enum class ReservedBlock(
	cidr: String,
	val locality: NetworkLocality,
) {
	/** RFC 1122. The unspecified address: Linux, and so Android, connects it to this device. */
	IPV4_UNSPECIFIED("0.0.0.0/32", NetworkLocality.LOOPBACK),

	/** RFC 1122. */
	IPV4_LOOPBACK("127.0.0.0/8", NetworkLocality.LOOPBACK),

	/** RFC 1918. 10.0.2.2, the Android emulator's alias for the developer machine, falls here. */
	IPV4_PRIVATE_10("10.0.0.0/8", NetworkLocality.PRIVATE_NETWORK),

	/** RFC 1918. */
	IPV4_PRIVATE_172("172.16.0.0/12", NetworkLocality.PRIVATE_NETWORK),

	/** RFC 1918. */
	IPV4_PRIVATE_192("192.168.0.0/16", NetworkLocality.PRIVATE_NETWORK),

	/** RFC 3927. */
	IPV4_LINK_LOCAL("169.254.0.0/16", NetworkLocality.PRIVATE_NETWORK),

	/** RFC 4291. The unspecified address, connected to this device as [IPV4_UNSPECIFIED] is. */
	IPV6_UNSPECIFIED("::/128", NetworkLocality.LOOPBACK),

	/** RFC 4291. */
	IPV6_LOOPBACK("::1/128", NetworkLocality.LOOPBACK),

	/** RFC 4193. */
	IPV6_UNIQUE_LOCAL("fc00::/7", NetworkLocality.PRIVATE_NETWORK),

	/** RFC 4291. */
	IPV6_LINK_LOCAL("fe80::/10", NetworkLocality.PRIVATE_NETWORK),
	;

	val block: CidrBlock = CidrBlock.parse(cidr)

	companion object {
		/** @return the locality of the block [address] falls in, or null for a public address */
		fun localityOf(address: IpAddress): NetworkLocality? = entries.firstOrNull { address in it.block }?.locality
	}
}
