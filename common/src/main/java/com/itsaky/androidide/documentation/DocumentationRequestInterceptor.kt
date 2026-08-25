/*
 *  This file is part of Code on the Go.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.documentation

import android.os.Environment.getExternalStorageDirectory
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.itsaky.androidide.utils.ContentTypeHeaders
import com.itsaky.androidide.utils.Environment
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Answers documentation requests from `documentation.db` in-process, so a WebView never opens a
 * socket to `WebServer` for them (ADFA-5176).
 *
 * Wire it into a WebView through `WebViewClient.shouldInterceptRequest`. The URLs do not change:
 * this matches the same `http://localhost:6174/...` space the server listens on, which is what lets
 * the strings.xml entries, ToolTipManager's link builder, and the plugin API contract stay as they
 * are. Anything this returns null for -- a `/pr/` developer endpoint, an unknown path, a read that
 * fails -- falls through to that server unchanged.
 *
 * Templated pages included: [DocumentationContentSource] renders them, so every documentation path
 * a WebView asks for is answered here.
 */
class DocumentationRequestInterceptor(
	private val contentSource: DocumentationContentSource,
) {
	private val log = LoggerFactory.getLogger(DocumentationRequestInterceptor::class.java)

	// Measurement switch: creating the sentinel puts documentation back on the local web server, so
	// one build can compare both transports. Read once, like the server's other file flags.
	private val disabled = File(getExternalStorageDirectory(), DISABLE_SENTINEL).exists()

	private val servedRequests = AtomicLong()
	private val servedBytes = AtomicLong()

	/**
	 * The response for [request], or null to let it go to the network. Called on WebView's own
	 * threads; [DocumentationContentSource] is what makes that safe.
	 */
	fun intercept(request: WebResourceRequest): WebResourceResponse? = contentFor(request)?.let { response(it) }

	/**
	 * The content to answer [request] with, or null when it is not this class's to answer. Split out
	 * from [intercept] so the decision can be tested without a framework WebResourceResponse.
	 */
	internal fun contentFor(request: WebResourceRequest): DocumentationContent? {
		if (disabled) return null
		if (!request.method.equals("GET", ignoreCase = true)) return null

		val url = request.url
		if (url.host != SERVER_HOST || url.port != SERVER_PORT) return null

		val path = url.path?.removePrefix("/").orEmpty()
		if (path.isEmpty() || path.startsWith("pr/")) return null

		val content =
			when (val lookup = contentSource.lookup(path)) {
				is DocumentationLookup.Found -> lookup.content
				else -> return null
			}

		servedRequests.incrementAndGet()
		servedBytes.addAndGet(content.bytes.size.toLong())
		if (log.isDebugEnabled) log.debug("Served '{}' in-process, {} bytes.", path, content.bytes.size)

		return content
	}

	/** What this instance has answered without a socket. */
	fun servedSummary(): String =
		if (disabled) {
			"in-process serving is off ($DISABLE_SENTINEL exists)"
		} else {
			"${servedRequests.get()} requests, ${servedBytes.get()} bytes served in-process"
		}

	private fun response(content: DocumentationContent): WebResourceResponse {
		val (type, charset) = mimeAndCharset(content.mimeType)
		return WebResourceResponse(type, charset, ByteArrayInputStream(content.bytes))
	}

	companion object {
		/**
		 * Splits a stored MIME type into what WebResourceResponse wants: the bare type, and the
		 * charset as its own value. The source hands back decompressed bytes -- a WebView does not
		 * decode an intercepted response -- so there is no Content-Encoding to declare either.
		 *
		 * Both the split and the default come from [ContentTypeHeaders], so the two transports cannot
		 * disagree about what a response says. This used to parse the charset itself, with the naive
		 * `substringAfter("charset=")` that ContentTypeHeaders warns against: for
		 * `text/html; note="charset=iso-8859-1"` it declared iso-8859-1 while the socket declared
		 * utf-8, and for `; Charset=UTF-8` it missed the parameter entirely (ADFA-5241).
		 */
		internal fun mimeAndCharset(mimeType: String): Pair<String, String?> = ContentTypeHeaders.typeAndCharset(mimeType)

		private const val DISABLE_SENTINEL = "Download/CodeOnTheGo.nointercept"
		private const val SERVER_HOST = "localhost"
		private const val SERVER_PORT = 6174

		/**
		 * The interceptor every WebView in the process shares, and with it one database handle and
		 * one copy of the compression dictionary. Deliberately separate from the source `WebServer`
		 * builds from its own config: the server's comes and goes with the activity that starts it,
		 * a WebView can outlive that, and neither should be able to close the other's handle. The
		 * cost of the second handle is SQLite's page cache plus the dictionary, a few MB.
		 */
		val shared: DocumentationRequestInterceptor? by lazy {
			// Null when Environment.init() has not run, which is a real state, not a defensive
			// nicety: DeviceProtectedApplicationLoader wraps that call in runCatching, and the
			// credential-protected loader returns before it when storage is not ready. DOC_DB is a
			// plain static File with no initializer, so it is null in both cases, and the non-null
			// parameter below turns that into an NPE at the first touch of this property. Declining
			// instead puts the request on the local web server, which is exactly what a null return
			// from intercept() already means everywhere else.
			val database = Environment.DOC_DB
			if (database == null) {
				LoggerFactory
					.getLogger(DocumentationRequestInterceptor::class.java)
					.warn("Environment.DOC_DB is not set; documentation requests stay on the web server.")
				null
			} else {
				DocumentationRequestInterceptor(
					DocumentationContentSource(
						database,
						File(getExternalStorageDirectory(), "Download/documentation.db"),
					),
				)
			}
		}
	}
}
