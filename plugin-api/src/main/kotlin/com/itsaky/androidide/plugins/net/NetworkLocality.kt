package com.itsaky.androidide.plugins.net

/**
 * Where a server lives, as far as its URL can tell. Exactly these three, so a consumer may `when`
 * over them exhaustively; adding one is a breaking change.
 */
enum class NetworkLocality {
	/** This device: `localhost`, `127.0.0.0/8`, `::1`, and the unspecified `0.0.0.0` and `::`. Cleartext never leaves the phone. */
	LOOPBACK,

	/**
	 * The local network: RFC 1918 ranges, link-local, IPv6 unique-local, `.local` names and dotless
	 * names such as `raspberrypi`. Cleartext crosses the LAN but not the internet.
	 */
	PRIVATE_NETWORK,

	/** Anything else. */
	PUBLIC,
}
