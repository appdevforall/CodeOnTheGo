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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import com.itsaky.androidide.utils.UrlManager
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URISyntaxException

/**
 * Keeps the app's WebViews on the device (ADFA-6589). Documentation pages reference fonts, YouTube
 * players and images on the internet, and a WebView fetches anything its client does not answer.
 * So every first-party WebView answers off-device requests with [block], sends tapped off-device
 * links through [openWithConsent], and turns Safe Browsing off with [disableSafeBrowsing].
 *
 * Not `WebSettings.blockNetworkLoads`: that would also stop requests to the local web server on
 * localhost:6174, which the `/pr/` endpoints and the `CodeOnTheGo.nointercept` fallback rely on.
 */
object OffDeviceRequests {
	private val log = LoggerFactory.getLogger(OffDeviceRequests::class.java)

	private val LOOPBACK_V4 = Regex("""127\.\d{1,3}\.\d{1,3}\.\d{1,3}""")

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
	 * Turns off Safe Browsing, which has Google Play services send the addresses a WebView loads to
	 * Google to be checked, charged to this app. It looks an address up even when [block] answers
	 * the request locally: on a Pixel 6 Pro, two documentation pages cost 15 KB of lookups with
	 * Safe Browsing on and nothing with it off. These WebViews load only documentation from the
	 * device, so there is nothing for it to check. Call it before the WebView loads anything.
	 */
	fun disableSafeBrowsing(settings: WebSettings) {
		settings.safeBrowsingEnabled = false
	}

	// The host [url] would connect to, or null if it stays on the device. The URLs a WebView hands
	// its client are already canonical (lowercase host, no backslashes), so java.net.URI reads the
	// host as Chromium does, userinfo included: http://localhost@example.com is example.com.
	private fun offDeviceHost(url: String): String? {
		val uri =
			try {
				URI(url)
			} catch (e: URISyntaxException) {
				return if (url.startsWith("http:", ignoreCase = true) || url.startsWith("https:", ignoreCase = true)) url else null
			}
		val scheme = uri.scheme?.lowercase()
		if (scheme != "http" && scheme != "https") return null
		val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return url
		return if (host == "localhost" || host == "::1" || LOOPBACK_V4.matches(host)) null else host
	}
}
