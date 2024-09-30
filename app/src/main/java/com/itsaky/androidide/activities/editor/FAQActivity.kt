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

import androidx.core.graphics.Insets
import android.os.Bundle
import android.view.View
import android.webkit.WebViewClient
import com.adfa.constants.CONTENT_KEY
import com.itsaky.androidide.R
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.databinding.ActivityFaqBinding

class FAQActivity : EdgeToEdgeIDEActivity() {

    private var _binding: ActivityFaqBinding? = null
    private val binding: ActivityFaqBinding
        get() = checkNotNull(_binding) {
            "FAQActivity has been destroyed"
        }

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

            // Set WebViewClient to handle page navigation within the WebView
            webView.webViewClient = WebViewClient()

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
            toolbar.paddingBottom
        )

        val webview: View = binding.webView
        webview.setPadding(
            webview.paddingLeft + insets.left,
            webview.paddingTop,
            webview.paddingRight + insets.right,
            webview.paddingBottom
        )
    }
}