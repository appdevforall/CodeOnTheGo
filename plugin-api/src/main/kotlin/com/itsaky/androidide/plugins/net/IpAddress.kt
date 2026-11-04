package com.itsaky.androidide.plugins.net

/** Keeps the low eight bits of an Int, the unsigned value of one byte. */
internal const val BYTE_MASK = 0xFF

/** An IP literal. Its locality is the [ReservedBlock] it falls in, or [NetworkLocality.PUBLIC]. */
internal sealed interface IpAddress : Host {
	/** Big-endian, each an unsigned byte in `0..255`. */
	val bytes: List<Int>

	override val locality: NetworkLocality
		get() = ReservedBlock.localityOf(this) ?: NetworkLocality.PUBLIC
}
