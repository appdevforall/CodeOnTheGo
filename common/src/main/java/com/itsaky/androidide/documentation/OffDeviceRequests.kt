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

import android.app.Activity
import android.content.Context
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import com.itsaky.androidide.utils.UrlManager
import com.itsaky.androidide.utils.findActivity
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URISyntaxException

/**
 * Keeps the documentation WebViews on the device (ADFA-6589). Documentation pages reference YouTube
 * players and images on the internet, and a WebView fetches anything its client does not answer.
 * So each documentation WebView answers off-device requests with [block], sends tapped off-device
 * links through [openWithConsent], and calls [keepOnDevice] before loading anything.
 *
 * [block] sees only what reaches `shouldInterceptRequest`. A page can still reach the network
 * without it: `<link rel=preconnect>` and `dns-prefetch` (or the same in a `Link:` header),
 * WebSockets, WebRTC, and each hop of a redirect. No documentation page uses any of them, and
 * neither WebServer nor DocumentationRequestInterceptor sends a redirect or a `Link:` header.
 *
 * Not `WebSettings.blockNetworkLoads`: that would also stop requests to the local web server on
 * localhost:6174, which the `/pr/` endpoints and the `CodeOnTheGo.nointercept` fallback rely on.
 */
object OffDeviceRequests {
	private val log = LoggerFactory.getLogger(OffDeviceRequests::class.java)

	private val LOOPBACK_V4 = Regex("""127\.\d{1,3}\.\d{1,3}\.\d{1,3}""")

	// Chromium ends an http(s) authority at / \ ? or #, so http://evil.com\@localhost/ is evil.com.
	private val AUTHORITY = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://([^/\\?#]*)""")

	/**
	 * Whether [url] is an http(s) URL whose host is not this device. Fails closed: an http(s) URL
	 * that cannot be parsed, or has no host, counts as off-device. Other schemes (data:, file:,
	 * about:, blob:) never leave the device.
	 */
	fun isOffDevice(url: String): Boolean = offDeviceHost(url) != null

	/**
	 * An empty local response for an off-device request.
	 *
	 * @return The response, or `null` for a request that stays on the device, so the caller goes on
	 *   as before: to the documentation interceptor, then the local web server.
	 */
	fun block(request: WebResourceRequest): WebResourceResponse? {
		val host = offDeviceHost(request.url.toString()) ?: return null
		log.info("Blocked an off-device request to {}", host)
		return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
	}

	/**
	 * Sends an off-device [url] through [UrlManager]'s consent dialog, which opens it outside the
	 * app. An [Activity], because [UrlManager.openUrl] asks only when given one: with any other
	 * context it opens the link without asking.
	 *
	 * @return `true` if [url] was off-device and so handled here.
	 */
	fun openWithConsent(
		activity: Activity,
		url: String,
	): Boolean {
		if (!isOffDevice(url)) return false
		UrlManager.openUrl(url, context = activity)
		return true
	}

	/**
	 * The first of [contexts] that is, or wraps, an [Activity], for [openWithConsent], which needs
	 * one to ask. A tooltip popup's own context may not be one, while the view it is anchored to
	 * belongs to an Activity.
	 */
	fun activityFor(vararg contexts: Context?): Activity? = contexts.firstNotNullOfOrNull { it?.findActivity() }

	/**
	 * Closes two ways a WebView reaches the network that [block] does not see. Call it before the
	 * WebView loads anything.
	 *
	 * Safe Browsing has Google Play services send Google hash prefixes of the addresses a WebView
	 * loads, charged to this app, even when [block] answers the request locally: on a Pixel 6 Pro,
	 * two documentation pages cost 15 KB of lookups with it on and nothing with it off.
	 *
	 * Service-worker fetches go to the ServiceWorkerClient rather than a WebView's client, so that one
	 * gets [block] too. It is process-wide and has no undo: from the first documentation WebView on,
	 * it covers every WebView in the app, plugins' included. A plugin that sets its own replaces it
	 * only until the next documentation WebView or tooltip popup sets this one again. No
	 * documentation page registers a service worker today.
	 */
	fun keepOnDevice(settings: WebSettings) {
		settings.safeBrowsingEnabled = false
		ServiceWorkerController.getInstance().setServiceWorkerClient(serviceWorkerClient)
	}

	private val serviceWorkerClient =
		object : ServiceWorkerClient() {
			override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = block(request)
		}

	// The host [url] would connect to (the URL itself when it has no host to read), or null if it
	// stays on the device. The URLs a WebView hands its client are already canonical (lowercase
	// host, no backslashes), so where java.net.URI parses one it finds the same host, userinfo
	// included: http://localhost@example.com is example.com. Leading and trailing spaces and control
	// characters, and tabs and line breaks anywhere, are dropped first, as the URL Standard has
	// Chromium drop them: "http\n://evil.com/" loads evil.com.
	private fun offDeviceHost(rawUrl: String): String? {
		val url = rawUrl.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
		val uri =
			try {
				URI(url)
			} catch (e: URISyntaxException) {
				return offDeviceHostOfUnparsed(url)
			}
		val scheme = uri.scheme?.lowercase()
		if (scheme != "http" && scheme != "https") return null
		val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return url
		return if (isThisDevice(host)) null else host
	}

	// java.net.URI rejects some characters a canonical URL keeps (a | in a query, a stray % in a
	// path), so read only scheme://authority. A URL even that cannot read stays off-device.
	private fun offDeviceHostOfUnparsed(url: String): String? {
		val match = AUTHORITY.find(url)
		if (match == null) {
			return if (url.startsWith("http:", ignoreCase = true) || url.startsWith("https:", ignoreCase = true)) url else null
		}
		val scheme = match.groupValues[1].lowercase()
		if (scheme != "http" && scheme != "https") return null
		val authority = match.groupValues[2].substringAfterLast('@')
		val host =
			if (authority.startsWith("[")) {
				authority.substringBefore(']').removePrefix("[")
			} else {
				authority.substringBefore(':')
			}.lowercase()
		return if (isThisDevice(host)) null else host.ifEmpty { url }
	}

	private fun isThisDevice(host: String) = host == "localhost" || host == "::1" || LOOPBACK_V4.matches(host)
}
