package com.itsaky.androidide.plugins.net

import java.util.Locale

/** Not a data class: `copy` would let callers skip [parse]'s validation. */
internal class DomainName private constructor(
	override val text: String,
) : Host {
	override val locality: NetworkLocality
		get() = Rule.entries.firstOrNull { it.matches(text) }?.locality ?: NetworkLocality.PUBLIC

	/**
	 * Names whose locality the name alone gives away, checked in declaration order. A name in none of
	 * them is [NetworkLocality.PUBLIC]: it is never resolved.
	 */
	private enum class Rule(
		val locality: NetworkLocality,
	) {
		/** RFC 6761 reserves it for this device. */
		LOCALHOST(NetworkLocality.LOOPBACK) {
			override fun matches(name: String) = relative(name) == LOCALHOST_NAME
		},

		/** RFC 6762 multicast DNS, answered only on the local link. */
		MULTICAST_DNS(NetworkLocality.PRIVATE_NETWORK) {
			override fun matches(name: String) = relative(name).endsWith(MULTICAST_DNS_SUFFIX)
		},

		/**
		 * A dotless name such as `raspberrypi` only resolves through the LAN's search domain. Not
		 * `raspberrypi.`: the trailing dot makes it absolute, so no search domain is appended.
		 */
		SINGLE_LABEL(NetworkLocality.PRIVATE_NETWORK) {
			override fun matches(name: String) = LABEL_SEPARATOR !in name
		},
		;

		abstract fun matches(name: String): Boolean
	}

	companion object {
		private const val LOCALHOST_NAME = "localhost"
		private const val MULTICAST_DNS_SUFFIX = ".local"
		private const val LABEL_SEPARATOR = '.'

		/** Underscore is allowed: LAN names use it though DNS forbids it. */
		private val NAME = Regex("[a-z0-9._-]+")

		/** [name] without the trailing root dot of an absolute name such as `localhost.`. */
		private fun relative(name: String) = name.removeSuffix(LABEL_SEPARATOR.toString())

		fun parse(text: String): DomainName? = text.lowercase(Locale.ROOT).takeIf(NAME::matches)?.let(::DomainName)
	}
}
