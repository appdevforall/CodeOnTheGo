package com.itsaky.androidide.plugins.net

import java.util.Locale

/** Not a data class: `copy` would let callers skip [parse]'s validation. */
internal class Ipv6Address private constructor(
	override val text: String,
	override val bytes: List<Int>,
) : IpAddress {
	/** An IPv4-mapped address reaches the embedded IPv4 host, so it lives where that host does. */
	override val locality: NetworkLocality
		get() = embeddedIpv4()?.locality ?: super.locality

	override val urlForm: String get() = "$OPEN_BRACKET$text$CLOSE_BRACKET"

	private fun embeddedIpv4(): Ipv4Address? {
		if (this !in IPV4_MAPPED) return null
		return Ipv4Address.fromBytes(bytes.takeLast(Ipv4Address.BYTE_COUNT))
	}

	companion object {
		const val OPEN_BRACKET = '['
		const val CLOSE_BRACKET = ']'

		private const val GROUP_COUNT = 8
		private const val HEX_RADIX = 16
		private const val GROUP_SEPARATOR = ':'
		private const val ZERO_RUN = "::"
		private const val ZONE_SEPARATOR = '%'
		private val HEX_GROUP = Regex("[0-9a-f]{1,4}")

		/** Lazy: parsing it needs this companion fully initialized. */
		private val IPV4_MAPPED by lazy { CidrBlock.parse("::ffff:0:0/96") }

		/** @param text the literal without brackets; a `%zone` suffix is dropped */
		fun parse(text: String): Ipv6Address? {
			val address = text.substringBefore(ZONE_SEPARATOR).lowercase(Locale.ROOT)
			val groups = expand(address) ?: return null
			return Ipv6Address(address, groups.flatMap(::bytesOf))
		}

		/** @return the [GROUP_COUNT] 16-bit groups with any `::` filled in, or null when malformed */
		private fun expand(address: String): List<Int>? {
			val halves = address.split(ZERO_RUN)
			return when (halves.size) {
				1 -> {
					groupsOf(address, endsAddress = true)?.takeIf { it.size == GROUP_COUNT }
				}

				2 -> {
					val (headText, tailText) = halves
					val head = groupsOf(headText, endsAddress = false) ?: return null
					val tail = groupsOf(tailText, endsAddress = true) ?: return null
					val zeros = GROUP_COUNT - head.size - tail.size
					if (zeros < 1) null else head + List(zeros) { 0 } + tail
				}

				else -> {
					null
				}
			}
		}

		/**
		 * The groups of a colon-separated run. When [endsAddress], its last part may be a dotted IPv4
		 * tail, worth two groups.
		 */
		private fun groupsOf(
			run: String,
			endsAddress: Boolean,
		): List<Int>? {
			if (run.isEmpty()) return emptyList()
			val parts = run.split(GROUP_SEPARATOR)
			return buildList {
				parts.forEachIndexed { index, part ->
					when {
						HEX_GROUP.matches(part) -> {
							add(part.toInt(HEX_RADIX))
						}

						endsAddress && index == parts.lastIndex -> {
							val ipv4 = Ipv4Address.parse(part) ?: return null
							addAll(ipv4.bytes.chunked(Short.SIZE_BYTES, ::groupOf))
						}

						else -> {
							return null
						}
					}
				}
			}
		}

		private fun bytesOf(group: Int): List<Int> = listOf(group shr Byte.SIZE_BITS, group and BYTE_MASK)

		private fun groupOf(bytes: List<Int>): Int = bytes.fold(0) { group, byte -> (group shl Byte.SIZE_BITS) or byte }
	}
}
