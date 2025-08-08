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

package org.appdevforall.layouteditor.activities

import android.os.Bundle
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import org.appdevforall.layouteditor.BaseActivity
import org.appdevforall.layouteditor.R
import org.appdevforall.layouteditor.databinding.ActivityHelpBinding
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
            toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

            val pageTitle = intent.getStringExtra(CONTENT_TITLE_KEY)
            val htmlContent = intent.getStringExtra(CONTENT_KEY)

            supportActionBar?.title = pageTitle ?: getString(R.string.help)

            // Enable JavaScript if required
            webView.settings.javaScriptEnabled = true

            // Set WebViewClient to handle page navigation within the WebView
            webView.webViewClient = WebViewClient()

            // Load the HTML file from the assets folder
            htmlContent?.let { webView.loadUrl(it) }

        }

    }

}
