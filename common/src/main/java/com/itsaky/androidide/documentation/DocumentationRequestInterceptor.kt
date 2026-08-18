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
import com.aayushatharva.brotli4j.Brotli4jLoader
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
 * are. Anything this returns null for -- a templated page, a `/pr/` developer endpoint, an unknown
 * path, a read that fails -- falls through to that server unchanged.
 *
 * Templated rows go to the server because rendering one needs Pebble, which lives in the `app`
 * module (see [DocumentationContentSource]). Only the Kotlin doc set uses templates -- 3,508 of the
 * database's 30,649 rows, none of them assets -- so a Kotlin page costs one connection for the page
 * itself and none for anything on it.
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
	fun intercept(request: WebResourceRequest): WebResourceResponse? {
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

		if (content.templateId > 0) return null

		servedRequests.incrementAndGet()
		servedBytes.addAndGet(content.bytes.size.toLong())
		if (log.isDebugEnabled) log.debug("Served '{}' in-process, {} bytes.", path, content.bytes.size)

		return response(content)
	}

	/** What this instance has answered without a socket. */
	fun servedSummary(): String =
		if (disabled) {
			"in-process serving is off ($DISABLE_SENTINEL exists)"
		} else {
			"${servedRequests.get()} requests, ${servedBytes.get()} bytes served in-process"
		}

	private fun response(content: DocumentationContent): WebResourceResponse {
		// WebResourceResponse wants the bare type; a charset travels in its own parameter. The
		// source hands back decompressed bytes -- a WebView does not decode an intercepted
		// response -- so there is no Content-Encoding to declare either.
		val type = content.mimeType.substringBefore(';').trim()
		val charset =
			content.mimeType
				.substringAfter("charset=", "")
				.substringBefore(';')
				.trim()
				.ifEmpty { if (type.startsWith("text/")) "utf-8" else null }

		return WebResourceResponse(type, charset, ByteArrayInputStream(content.bytes))
	}

	companion object {
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
		val shared: DocumentationRequestInterceptor by lazy {
			Brotli4jLoader.ensureAvailability()

			DocumentationRequestInterceptor(
				DocumentationContentSource(
					Environment.DOC_DB,
					File(getExternalStorageDirectory(), "Download/documentation.db"),
				),
			)
		}
	}
}
