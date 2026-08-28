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

package com.itsaky.androidide.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.itsaky.androidide.R
import com.itsaky.androidide.documentation.DocumentationRequestInterceptor


class IDETooltipWebviewFragment : Fragment() {
	private lateinit var webView: WebView
	private lateinit var website : String
	// A getter, not an initializer and not `by lazy`: an initializer here runs during Fragment
	// construction on the main thread, where the interceptor's sentinel check is a disk read
	// StrictMode reports and a missing database is a crash before the view exists -- and `lazy`
	// memoizes even the null that means Environment.DOC_DB was not set *yet*, pinning in-process
	// serving off for the fragment's life. Only a non-null is cached, so the companion's
	// retry-on-null contract holds. See HelpActivity for the same note.
	private var cachedDocumentation: DocumentationRequestInterceptor? = null
	private val documentation: DocumentationRequestInterceptor?
		get() = cachedDocumentation ?: DocumentationRequestInterceptor.shared?.also { cachedDocumentation = it }

	//This warning is unnecessary because we control the content
	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		super.onCreateView(inflater, container, savedInstanceState)
		Log.d(Companion.TAG, "IDETooltipWebviewFragment\\\\onCreateView called")
		// Handle back press using OnBackPressedCallback
		requireActivity().onBackPressedDispatcher.addCallback(
			viewLifecycleOwner,
			object : OnBackPressedCallback(true) {
				override fun handleOnBackPressed() {
					if (webView.canGoBack()) {
						webView.goBack()
					} else {
						activity?.runOnUiThread {
							webView.clearHistory()
							webView.loadUrl("about:blank")
							webView.destroy()
						}
						parentFragmentManager.popBackStack()
						isEnabled =
							false // Disable this callback to let the default back press behavior occur
					}
				}
			})

		website = arguments?.getString(MainFragment.KEY_TOOLTIP_URL).orEmpty()

		val safeContext = ContextThemeWrapper(requireContext().applicationContext, requireContext().theme)
		val view = LayoutInflater.from(safeContext).inflate(R.layout.fragment_idetooltipwebview, container, false)

		webView = view.findViewById(R.id.IDETooltipWebView)

		// Set a WebViewClient to handle loading pages
		webView.webViewClient = object : WebViewClient() {
			// ADFA-5176: documentation comes from the database in-process; anything this declines
			// still goes to the local web server.
			override fun shouldInterceptRequest(
				view: WebView,
				request: WebResourceRequest,
			): WebResourceResponse? = documentation?.intercept(request) ?: super.shouldInterceptRequest(view, request)

			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
				// Allow loading of local assets files
				if (request.url.toString().startsWith("file:///android_asset/")) {
					view.loadUrl(request.url.toString())
					return true
				}
				return super.shouldOverrideUrlLoading(view, request)
			}
		}

		// Set up WebChromeClient to support JavaScript
//        webView.webChromeClient = WebChromeClient()
		webView.scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
		webView.scrollBarDefaultDelayBeforeFade = 1000


		// Enable JavaScript if needed
		webView.settings.javaScriptEnabled = true

		// Load the HTML file from the assets folder
		webView.loadUrl(website)
		return view
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		Log.d(Companion.TAG, "IDETooltipWebViewFragment\\\\onViewCreated called")
	}

	override fun onDestroyView() {
		super.onDestroyView()
		// Clean up the WebView in Fragment
		if(webView.isVisible) {
			webView.clearHistory()
			webView.loadUrl("about:blank")
			webView.destroy()
		}

	}

	companion object {
		private const val TAG = "IDETooltipWebViewFragment"
	}


}
