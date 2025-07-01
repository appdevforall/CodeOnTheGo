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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.itsaky.androidide.R
import java.net.URL


class IDETooltipWebviewFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var website : String

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

        website = arguments?.getString(/* key = */ MainFragment.KEY_TOOLTIP_URL).toString()

        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_idetooltipwebview, container, false)

        // Initialize the WebView
        webView = view.findViewById(R.id.IDETooltipWebView)

        // Set a WebViewClient to handle loading pages
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                Log.d(TAG, "WebView trying to load URL: $url")
                
                // Allow loading of local assets files
                if (url.startsWith("file:///android_asset/")) {
                    Log.d(TAG, "Loading local asset: $url")
                    view.loadUrl(url)
                    return true
                }
                // Allow loading of localhost URLs (our local web server)
                if (url.startsWith("http://localhost:6174/")) {
                    Log.d(TAG, "Loading localhost URL: $url")
                    view.loadUrl(url)
                    return true
                }
                Log.d(TAG, "Not handling URL: $url")
                return super.shouldOverrideUrlLoading(view, request)
            }
            
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                Log.e(TAG, "WebView error: $errorCode - $description for URL: $failingUrl")
                super.onReceivedError(view, errorCode, description, failingUrl)
            }
        }

        // Set up WebChromeClient to support JavaScript
//        webView.webChromeClient = WebChromeClient()
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.allowFileAccess = true
        webView.settings.allowUniversalAccessFromFileURLs = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
