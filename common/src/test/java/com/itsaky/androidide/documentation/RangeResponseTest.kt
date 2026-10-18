package com.itsaky.androidide.documentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Covers the answer to a `Range` request (ADFA-3305). [delivered] plays the WebView's part, as
 * Chromium's `InputStreamReader` does it: skip to the range's first byte, then read to the end.
 */
class RangeResponseTest {
	private val content = ByteArray(1000) { (it * 7).toByte() }

	private fun delivered(
		reply: RangeResponse,
		first: Int,
	): ByteArray = ByteArrayInputStream(content, 0, reply.streamLength).apply { skip(first.toLong()) }.readBytes()

	private fun assertWhole(reply: RangeResponse) {
		assertThat(reply.status).isEqualTo(200)
		assertThat(reply.headers).containsExactly("Accept-Ranges", "bytes")
		assertThat(reply.streamLength).isEqualTo(content.size)
		assertThat(reply.sentLength).isEqualTo(content.size)
		assertThat(reply.rejected).isFalse()
	}

	private fun assertPartial(
		range: String,
		first: Int,
		last: Int,
	) {
		val reply = RangeResponse.of(range, content.size)

		assertThat(reply.status).isEqualTo(206)
		assertThat(reply.reason).isEqualTo("Partial Content")
		assertThat(reply.headers).containsExactly("Accept-Ranges", "bytes", "Content-Range", "bytes $first-$last/1000")
		assertThat(delivered(reply, first)).isEqualTo(content.copyOfRange(first, last + 1))
		assertThat(reply.sentLength).isEqualTo(last - first + 1)
		assertThat(reply.rejected).isFalse()
	}

	@Test
	fun `a request with no range gets the whole content, and is told ranges work`() {
		assertWhole(RangeResponse.of(null, content.size))
	}

	// The case behind the frozen clips: a player's re-request from partway through the file.
	@Test
	fun `an open range from partway through gets a 206 from that byte`() {
		assertPartial("bytes=600-", 600, 999)
	}

	// The WebView reads the stream to its end, so the stream itself has to stop at the last byte.
	@Test
	fun `a closed range delivers no bytes past its end`() {
		assertPartial("bytes=0-99", 0, 99)
		assertPartial("bytes=250-260", 250, 260)
		assertPartial("bytes=5-5", 5, 5)
	}

	@Test
	fun `a suffix range delivers the last bytes`() {
		assertPartial("bytes=-300", 700, 999)
	}

	@Test
	fun `a range running past the end is cut at the end`() {
		assertPartial("bytes=900-5000", 900, 999)
		assertPartial("bytes=-5000", 0, 999)
	}

	@Test
	fun `the unit is matched regardless of case, and blanks and empty items are skipped`() {
		assertPartial(" Bytes = 10 - 19 ", 10, 19)
		assertPartial("bytes=10-19,", 10, 19)
		assertPartial("bytes=10-19, ", 10, 19)
	}

	// Chromium holds "not given" as -1, so a last position of -1 leaves the range open.
	// Chromium's integer parser takes a leading plus, so a stricter digit check here would answer a
	// range the WebView still cuts with a 200 that starts mid-file.
	@Test
	fun `a plus sign is read as the WebView reads it`() {
		assertPartial("bytes=+5-", 5, 999)
	}

	@Test
	fun `a last position of -1 reads as an open range, as it does in the WebView`() {
		assertPartial("bytes=5--1", 5, 999)
	}

	@Test
	fun `several ranges get the whole content, which is what the WebView sends for them`() {
		assertWhole(RangeResponse.of("bytes=0-9,20-29", content.size))
	}

	// The WebView fails these requests itself, so nothing is sent whatever the response says. It
	// checks every item of several ranges, and trims only spaces and tabs.
	@Test
	fun `a range the WebView rejects gets a 200 and counts as nothing sent`() {
		val rejected =
			listOf(
				"bytes=1000-",
				"bytes=9-0",
				"bytes=-0",
				"bytes=abc",
				"items=0-9",
				"bytes 0-9",
				"bytes=",
				"bytes=0-9,abc",
				"bytes=0-9,9-0",
				"bytes=5-\u000b",
			)
		for (range in rejected) {
			val reply = RangeResponse.of(range, content.size)

			assertThat(reply.status).isEqualTo(200)
			assertThat(reply.streamLength).isEqualTo(content.size)
			assertThat(reply.sentLength).isEqualTo(0)
			assertThat(reply.rejected).isTrue()
		}
	}

	@Test
	fun `empty content is never cut`() {
		for (range in listOf("bytes=0-", "bytes=-5")) {
			val reply = RangeResponse.of(range, 0)

			assertThat(reply.status).isEqualTo(200)
			assertThat(reply.streamLength).isEqualTo(0)
		}
	}

	@Test
	fun `finds the range header whatever the case of its name`() {
		assertThat(DocumentationRequestInterceptor.rangeHeader(mapOf("range" to "bytes=0-"))).isEqualTo("bytes=0-")
		assertThat(DocumentationRequestInterceptor.rangeHeader(mapOf("Range" to "bytes=5-"))).isEqualTo("bytes=5-")
		assertThat(DocumentationRequestInterceptor.rangeHeader(mapOf("Accept" to "*/*"))).isNull()
		assertThat(DocumentationRequestInterceptor.rangeHeader(null)).isNull()
	}
}
