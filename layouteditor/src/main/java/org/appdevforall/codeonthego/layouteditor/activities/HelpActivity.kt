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

package org.appdevforall.codeonthego.layouteditor.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import org.appdevforall.codeonthego.layouteditor.BaseActivity
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.databinding.ActivityHelpBinding
import org.adfa.constants.CONTENT_KEY
import org.adfa.constants.CONTENT_TITLE_KEY

class HelpActivity : BaseActivity() {

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        init()

    }

    private fun init() {
        binding = ActivityHelpBinding.inflate(layoutInflater)
        with(binding) {
            setContentView(root)
            setSupportActionBar(toolbar)
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            toolbar.setNavigationOnClickListener { handleBackNavigation() }

            val pageTitle = intent.getStringExtra(CONTENT_TITLE_KEY)
            val htmlContent = intent.getStringExtra(CONTENT_KEY)

            supportActionBar?.title = pageTitle ?: getString(R.string.help)

            // Enable JavaScript if required
            webView.settings.javaScriptEnabled = true

            // Set WebViewClient to handle page navigation within the WebView
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, url: String?): Boolean {
                    return handleUrlLoading( url)
                }
            }

            // Load the HTML file from the assets folder
            htmlContent?.let { webView.loadUrl(it) }

        }

        // Set up back navigation callback for system back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })
    }

    private fun handleUrlLoading(url: String?): Boolean {
        url ?: return false

        val externalSchemes = listOf("mailto:", "tel:", "sms:")

        return when {
            externalSchemes.any { url.startsWith(it) } -> {
                handleExternalScheme(url)
                true
            }
            else -> false
        }
    }

    private fun handleExternalScheme(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        }.onFailure { e ->
            Log.e("HelpActivity", "Failed to open URL: $url", e)
        }
    }

    private fun handleBackNavigation() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            finish()
        }
    }

}
