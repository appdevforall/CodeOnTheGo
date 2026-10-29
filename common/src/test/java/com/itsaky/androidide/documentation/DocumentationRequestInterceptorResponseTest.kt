package com.itsaky.androidide.documentation

import android.net.Uri
import android.webkit.WebResourceRequest
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Covers what [DocumentationRequestInterceptor.intercept] hands the WebView, which needs the
 * framework's real `WebResourceResponse`: a 206 lost between [RangeResponse] and the response is
 * how four looping clips froze on a phone (ADFA-3305). The WebView itself skips to the range's
 * first byte, so the stream is expected to hold the content up to the range's last byte.
 *
 * Conscrypt off: Robolectric otherwise installs it as the JVM's first security provider and leaves
 * it there, and SelfSignedCertTest, run later in the same JVM, then parses its certificate with it
 * and fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DocumentationRequestInterceptorResponseTest {
	private val source =
		mockk<DocumentationContentSource>(relaxed = true) {
			every { lookupRequestPath(any()) } answers {
				RequestLookup(firstArg(), DocumentationLookup.Found(DocumentationContent("abcdef".toByteArray(), "video/webm")))
			}
		}

	private fun request(headers: Map<String, String>): WebResourceRequest =
		mockk {
			every { method } returns "GET"
			every { url } returns Uri.parse("http://localhost:6174/a/clip.webm")
			every { requestHeaders } returns headers
		}

	@Test
	fun `a ranged request gets a 206, its range headers, and a stream that stops at the range`() {
		val response = DocumentationRequestInterceptor(source).intercept(request(mapOf("Range" to "bytes=1-2")))!!

		assertThat(response.statusCode).isEqualTo(206)
		assertThat(response.reasonPhrase).isEqualTo("Partial Content")
		assertThat(response.responseHeaders).containsExactly("Accept-Ranges", "bytes", "Content-Range", "bytes 1-2/6")
		assertThat(response.mimeType).isEqualTo("video/webm")
		assertThat(response.data.readBytes().toString(Charsets.UTF_8)).isEqualTo("abc")
	}

	@Test
	fun `a request with no range gets a 200 and the whole content`() {
		val response = DocumentationRequestInterceptor(source).intercept(request(emptyMap()))!!

		assertThat(response.statusCode).isEqualTo(200)
		assertThat(response.responseHeaders).containsExactly("Accept-Ranges", "bytes")
		assertThat(response.data.readBytes().toString(Charsets.UTF_8)).isEqualTo("abcdef")
	}
}
