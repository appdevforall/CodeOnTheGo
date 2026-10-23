package com.itsaky.androidide.documentation

import java.util.Locale

/**
 * The status, headers and stream length of an in-process response, given the request's `Range`.
 *
 * The WebView, not this code, cuts the body to the range. For a single range it can satisfy,
 * Chromium's `AndroidStreamReaderURLLoader` skips the response stream to the range's first byte,
 * sets `Content-Length` to the range's length, applies the response's own status and headers, and
 * then reads the stream to its end (`components/embedder_support/android/util`, read at WebView
 * 155.0.8059.43). Without a status of ours, a ranged request got "200 OK" with a body that
 * began mid-file, which a media player takes for the file from byte 0: looping `<video>` clips
 * froze after a re-request during playback (ADFA-3305). So a satisfiable range gets a 206 with
 * `Content-Range`, and a stream that stops at the range's last byte, since the WebView reads on
 * past it.
 *
 * @property status The HTTP status code.
 * @property reason The reason phrase for [status].
 * @property headers The headers to add. Neither `Content-Length` nor `Content-Type` is among them:
 * the WebView sets both, and adds these beside its own rather than in place of them.
 * @property streamLength How many bytes of the content, from its start, the response stream holds.
 * @property sentLength How many bytes the WebView sends: the range's length for a 206, none for a
 * range it rejects, since it fails that request, and all of the content otherwise.
 * @property rejected Whether the WebView fails this request itself, for a malformed or
 * unsatisfiable range, so that nothing reaches the page.
 */
internal data class RangeResponse(
	val status: Int,
	val reason: String,
	val headers: Map<String, String>,
	val streamLength: Int,
	val sentLength: Int,
	val rejected: Boolean = false,
) {
	companion object {
		/**
		 * The response to [range], a request's `Range` value or null, for content of [length] bytes.
		 *
		 * Parsed as the WebView parses it (`net::HttpUtil::ParseRangeHeader`, then
		 * `net::HttpByteRange::ComputeBounds`), so the two agree on which bytes are meant. Anything
		 * but one satisfiable range gets all the content with a 200: the WebView ignores several
		 * ranges and sends everything, and fails a malformed or unsatisfiable one itself, whatever
		 * this returns, so that one counts as nothing sent. Empty content is never cut, by the
		 * WebView or here.
		 */
		fun of(
			range: String?,
			length: Int,
		): RangeResponse {
			val whole = RangeResponse(200, "OK", mapOf(ACCEPT_RANGES), length, length)
			if (range == null || length == 0) return whole

			val rejected = whole.copy(sentLength = 0, rejected = true)
			val single = (ranges(range) ?: return rejected).singleOrNull() ?: return whole
			val bounds = single.boundsWithin(length.toLong()) ?: return rejected

			return RangeResponse(
				206,
				"Partial Content",
				mapOf(ACCEPT_RANGES, "Content-Range" to "bytes ${bounds.first}-${bounds.last}/$length"),
				(bounds.last + 1).toInt(),
				(bounds.last - bounds.first + 1).toInt(),
			)
		}

		private val ACCEPT_RANGES = "Accept-Ranges" to "bytes"
		private const val NOT_SPECIFIED = -1L

		/** `HttpUtil::ParseRangeHeader`: the ranges [header] names, or null where the WebView rejects it. */
		private fun ranges(header: String): List<ByteRange>? {
			val equals = header.indexOf('=')
			if (equals < 0 || header.substring(0, equals).trimLws().lowercase(Locale.ROOT) != "bytes") return null

			val values =
				header
					.substring(equals + 1)
					.split(',')
					.map { it.trimLws() }
					.filter { it.isNotEmpty() }
			return values.map { parseRange(it) ?: return null }.ifEmpty { null }
		}

		private fun parseRange(value: String): ByteRange? {
			val minus = value.indexOf('-')
			if (minus < 0) return null

			val firstText = value.substring(0, minus).trimLws()
			val lastText = value.substring(minus + 1).trimLws()
			val first = if (firstText.isEmpty()) NOT_SPECIFIED else firstText.toLongOrNull() ?: return null
			val range =
				when {
					lastText.isNotEmpty() -> {
						val last = lastText.toLongOrNull() ?: return null
						if (firstText.isEmpty()) ByteRange(suffix = last) else ByteRange(first = first, last = last)
					}

					firstText.isEmpty() -> {
						return null
					}

					else -> {
						ByteRange(first = first)
					}
				}
			return range.takeIf { it.isValid }
		}

		private fun String.trimLws() = trim(' ', '\t')
	}

	/**
	 * One range as `net::HttpByteRange` holds it, -1 standing for "not given" as it does there; so
	 * `bytes=5--1` reads as `bytes=5-` here too.
	 */
	private class ByteRange(
		val first: Long = NOT_SPECIFIED,
		val last: Long = NOT_SPECIFIED,
		val suffix: Long = NOT_SPECIFIED,
	) {
		val isValid: Boolean
			get() = suffix > 0 || (first >= 0 && (last == NOT_SPECIFIED || last >= first))

		/** `HttpByteRange::ComputeBounds`: the byte positions meant within [size], or null if none are. */
		fun boundsWithin(size: Long): LongRange? =
			when {
				!isValid -> null
				suffix != NOT_SPECIFIED -> (size - minOf(size, suffix))..(size - 1)
				first < size -> first..(if (last == NOT_SPECIFIED) size - 1 else minOf(size - 1, last))
				else -> null
			}
	}
}
