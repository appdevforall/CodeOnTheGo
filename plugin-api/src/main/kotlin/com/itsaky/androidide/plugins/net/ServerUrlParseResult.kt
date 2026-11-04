package com.itsaky.androidide.plugins.net

/** The outcome of [ServerUrl.parse]: a usable [ServerUrl], or the [Invalid.Reason] it is not one. */
sealed interface ServerUrlParseResult {
	data class Valid(
		val url: ServerUrl,
	) : ServerUrlParseResult

	data class Invalid(
		val reason: Reason,
	) : ServerUrlParseResult {
		/**
		 * Why a URL was refused. Carries no text, so each plugin words it in its own strings. More
		 * reasons may be added; a consumer must give a `when` over them an `else` branch.
		 */
		enum class Reason {
			/** Null, empty or whitespace only. */
			BLANK,

			/** Not `scheme://host[:port][/path][?query][#fragment]`, or a bad port or IP literal. */
			MALFORMED,

			/** Well formed, but the scheme is neither `http` nor `https`. */
			UNSUPPORTED_SCHEME,

			/** Names no host: `http://` or `https:///v1`. */
			NO_HOST,
		}
	}
}
