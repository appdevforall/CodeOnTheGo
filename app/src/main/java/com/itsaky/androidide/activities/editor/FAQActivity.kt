/*
 *  This file is part of AndroidIDE.
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

package com.itsaky.androidide.activities.editor

import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.Insets
import com.itsaky.androidide.R
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.databinding.ActivityFaqBinding
import com.itsaky.androidide.documentation.DocumentationRequestInterceptor
import org.adfa.constants.CONTENT_KEY

class FAQActivity : EdgeToEdgeIDEActivity() {
	@Suppress("ktlint:standard:backing-property-naming")
	private var _binding: ActivityFaqBinding? = null
	private val binding: ActivityFaqBinding
		get() =
			checkNotNull(_binding) {
				"FAQActivity has been destroyed"
			}

	/**
	 * Inflates the FAQ activity layout and returns its root view.
	 *
	 * @return The root view of the inflated FAQ layout.
	 */
	override fun bindLayout(): View {
		_binding = ActivityFaqBinding.inflate(layoutInflater)
		return binding.root
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		with(binding) {
			setSupportActionBar(toolbar)
			supportActionBar!!.setTitle(R.string.faq_activity_title)
			supportActionBar!!.setDisplayHomeAsUpEnabled(true)
			toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

			val htmlContent = intent.getStringExtra(CONTENT_KEY)

//            htmlContent?.let {
//                webView.clearCache(true)
//                webView.loadDataWithBaseURL(null, it, "text/html", "UTF-8", null)
//            }
			// Enable JavaScript if required
			webView.settings.javaScriptEnabled = true

			// Set WebViewClient to handle page navigation within the WebView. ADFA-5176: it answers
			// documentation from the database in-process, falling through to the local web server
			// for anything it declines.
			webView.webViewClient =
				object : WebViewClient() {
					/**
					 * Intercepts documentation resource requests when a shared interceptor is available.
					 *
					 * @param view The WebView making the request.
					 * @param request The requested web resource.
					 * @return The intercepted response, or the default WebView response when the request is not intercepted.
					 */
					override fun shouldInterceptRequest(
						view: WebView,
						request: WebResourceRequest,
					): WebResourceResponse? =
						DocumentationRequestInterceptor.shared?.intercept(request)
							?: super.shouldInterceptRequest(view, request)
				}

			// Load the HTML file from the assets folder
			htmlContent?.let { webView.loadUrl(it) }
		}
	}

	override fun onApplySystemBarInsets(insets: Insets) {
		val toolbar: View = binding.toolbar
		toolbar.setPadding(
			toolbar.paddingLeft + insets.left,
			toolbar.paddingTop,
			toolbar.paddingRight + insets.right,
			toolbar.paddingBottom,
		)

		val webview: View = binding.webView
		webview.setPadding(
			webview.paddingLeft + insets.left,
			webview.paddingTop,
			webview.paddingRight + insets.right,
			webview.paddingBottom,
		)
	}
}
