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
		 * Serves a documentation request from the in-process content source.
		 *
		 * @return The WebView response, or `null` to allow the request to fall back to the web server.
		 */
	fun intercept(request: WebResourceRequest): WebResourceResponse? =
		try {
			contentFor(request)?.let { response(it) }
		} catch (e: Throwable) {
			// Throwable, not Exception. The documented contract is that anything this returns null for
			// falls through to the web server, and that only holds for values, not throws: decoding the
			// largest bundled row (8.8 MB over nine chunks) can raise OutOfMemoryError and a pathological
			// template a StackOverflowError, neither of which lookup()'s catch (e: Exception) sees. This
			// runs on a Chromium thread, where an escaping Error takes the process down -- the socket
			// transport confined the same failure to one 500 (ADFA-5176 review).
			log.error("Serving {} in-process failed; falling back to the web server", request.url, e)
			null
		}

	/**
	 * Resolves documentation content for a request eligible for in-process serving.
	 *
	 * @return The matching documentation content, or `null` when the request is unsupported or no content is found.
	 */
	internal fun contentFor(request: WebResourceRequest): DocumentationContent? {
		if (disabled) return null
		if (!request.method.equals("GET", ignoreCase = true)) return null

		val url = request.url
		if (url.host != SERVER_HOST || url.port != SERVER_PORT) return null

		// encodedPath, not path: stored Content.path rows are percent-encoded, so the raw target is
		// what matches them. The source falls back to the decoded form on a miss, identically for
		// both transports (see DocumentationContentSource.lookupRequestPath).
		val path = url.encodedPath?.removePrefix("/").orEmpty()
		if (path.isEmpty() || path.startsWith("pr/")) return null

		val content =
			when (val lookup = contentSource.lookupRequestPath(path).lookup) {
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

	/** Drops this interceptor's compiled templates; the source recompiles them on demand. */
	fun clearTemplateCache() = contentSource.clearTemplateCache()

	/**
	 * Creates a WebView resource response from documentation content.
	 *
	 * @param content The documentation content to serve.
	 * @return A response containing the content bytes and parsed MIME metadata.
	 */
	private fun response(content: DocumentationContent): WebResourceResponse {
		val (type, charset) = mimeAndCharset(content.mimeType)
		return WebResourceResponse(type, charset, ByteArrayInputStream(content.bytes))
	}

	companion object {
		/**
 * Separates a MIME type from its optional charset.
 *
 * @param mimeType The stored MIME type and optional charset declaration.
 * @return A pair containing the MIME type and nullable charset.
 */
		internal fun mimeAndCharset(mimeType: String): Pair<String, String?> = ContentTypeHeaders.typeAndCharset(mimeType)

		private const val DISABLE_SENTINEL = "Download/CodeOnTheGo.nointercept"
		private const val SERVER_HOST = "localhost"
		private const val SERVER_PORT = 6174

		// Not `lazy`: it memoizes whatever the initializer returned, including null, and null here is
		// a transient state rather than a verdict. Environment.init() runs inside the loader coroutine
		// -- DeviceProtectedApplicationLoader wraps it in runCatching, and the credential-protected
		// loader returns early when storage is not ready -- so a WebView that asks during direct boot
		// sees DOC_DB unset. Caching that answer disabled in-process documentation for the whole
		// process, and since WebServer is started only by MainActivity and stopped in its onDestroy,
		// opening Help from the editor afterwards then had nothing to fall back to (ADFA-5176 review).
		// Only a successful construction is cached; a null is retried on the next request.
		@Volatile
		private var sharedInstance: DocumentationRequestInterceptor? = null

		private val log = LoggerFactory.getLogger(DocumentationRequestInterceptor::class.java)

		/**
		 * The interceptor every WebView in the process shares, and with it one database handle and
		 * one copy of the compression dictionary. Deliberately separate from the source `WebServer`
		 * builds from its own config: the server's comes and goes with the activity that starts it,
		 * a WebView can outlive that, and neither should be able to close the other's handle. The
		 * cost of the second handle is SQLite's page cache plus the dictionary, a few MB.
		 *
		 * Null while `Environment.DOC_DB` is still unset, which puts that request on the local web
		 * server and leaves the next one free to try again.
		 */
		val shared: DocumentationRequestInterceptor?
			get() {
				sharedInstance?.let { return it }
				return synchronized(this) {
					sharedInstance ?: run {
						val database = Environment.DOC_DB
						if (database == null) {
							log.warn("Environment.DOC_DB is not set yet; this documentation request stays on the web server.")
							null
						} else {
							DocumentationRequestInterceptor(
								DocumentationContentSource(
									database,
									File(getExternalStorageDirectory(), "Download/documentation.db"),
								),
							).also { sharedInstance = it }
						}
					}
				}
			}

		/**
		 * True once [shared] has been built, without building it. For a caller that wants to read
		 * [shared] cheaply -- once built, the getter's first line returns it with no lock and no
		 * stat -- but must not be the touch that constructs it (the constructor stats external
		 * storage for the sentinel, banned on the main thread).
		 */
		val isSharedInitialized: Boolean
			get() = sharedInstance != null

		/**
		 * Clears [shared]'s compiled templates, for the developer clear-cache sentinel. A no-op
		 * when [shared] has never been touched: it is not created just to empty a cache that does
		 * not exist yet.
		 */
		fun clearSharedTemplateCache() {
			// sharedInstance, not shared: asking must not construct the interceptor as a side effect.
			sharedInstance?.clearTemplateCache()
		}
	}
}
