/*
 *  This file is part of CodeOnTheGo.
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
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import org.adfa.constants.CONTENT_KEY
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.app.BaseIDEActivity
import com.itsaky.androidide.common.databinding.ActivityHelpBinding
import com.itsaky.androidide.utils.isSystemInDarkMode
import org.adfa.constants.CONTENT_TITLE_KEY

class HelpActivity : BaseIDEActivity() {

    private var _binding: ActivityHelpBinding? = null
    private val binding: ActivityHelpBinding
        get() = checkNotNull(_binding) {
            "HelpActivity has been destroyed"
        }

    override fun bindLayout(): View {
        _binding = ActivityHelpBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        with(binding) {
            setSupportActionBar(toolbar)
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

            // Set status bar icons to be dark in light mode and light in dark mode
            WindowCompat.getInsetsController(this@HelpActivity.window, this@HelpActivity.window.decorView).apply {
                isAppearanceLightStatusBars = !isSystemInDarkMode()
                isAppearanceLightNavigationBars = !isSystemInDarkMode()
            }

            val pageTitle = intent.getStringExtra(CONTENT_TITLE_KEY)
            val htmlContent = intent.getStringExtra(CONTENT_KEY)

            supportActionBar?.title = pageTitle ?: getString(R.string.help)

            // Configure WebView settings for localhost access
            webView.settings.javaScriptEnabled = true
            webView.settings.allowFileAccess = true
            webView.settings.allowFileAccessFromFileURLs = true
            webView.settings.allowUniversalAccessFromFileURLs = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Set WebViewClient to handle page navigation within the WebView
            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    android.util.Log.d("HelpActivity", "Page started loading: $url")
                }
                
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    android.util.Log.d("HelpActivity", "Page finished loading: $url")
                }
                
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, url: String?): Boolean {
                    url?.let {
                        // Allow localhost URLs to load directly
                        if (it.startsWith("http://localhost:6174/")) {
                            view?.loadUrl(it)
                            return true
                        }
                    }
                    return super.shouldOverrideUrlLoading(view, url)
                }
                
                override fun onReceivedError(view: android.webkit.WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    android.util.Log.e("HelpActivity", "Error loading URL: $failingUrl, Error: $description")
                    // Show error message to user
                    view?.loadData("""
                        <html><body>
                        <h3>Error Loading Content</h3>
                        <p>Unable to load: $failingUrl</p>
                        <p>Error: $description</p>
                        </body></html>
                    """.trimIndent(), "text/html", "UTF-8")
                }
            }

            // Load the HTML file from the assets folder
            htmlContent?.let { url ->
                android.util.Log.d("HelpActivity", "Loading URL: $url")
                webView.loadUrl(url)
            }
        }
    }

}