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
import androidx.fragment.app.Fragment
import com.itsaky.androidide.R
import java.net.URL


class IDETooltipWebviewFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var website : String

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
        webView.canGoBack()
        webView.canGoForward()
        webView.settings.allowFileAccessFromFileURLs
        webView.settings.allowFileAccess
        webView.settings.allowUniversalAccessFromFileURLs


        // Enable JavaScript if needed
        webView.settings.javaScriptEnabled = true

        // Load the HTML file from the assets folder
        webView.loadUrl(website)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(Companion.TAG, "IDETooltipWebviewFragment\\\\onViewCreated called")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up the WebView in Fragment
        webView.clearHistory()
        webView.loadUrl("about:blank")
        webView.destroy()
    }

    companion object {
        private const val TAG = "IDETooltipWebviewFragment"
    }


}
