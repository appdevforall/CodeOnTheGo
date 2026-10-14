package com.itsaky.androidide.plugins.net

/** A host as written in a URL. Each kind parses its own text and owns the rule for where it lives. */
internal sealed interface Host {
	/** Canonical text: lowercased, without IPv6 brackets or zone id. */
	val text: String

	val locality: NetworkLocality

	/** How the host is written inside a URL's authority. */
	val urlForm: String get() = text
}
