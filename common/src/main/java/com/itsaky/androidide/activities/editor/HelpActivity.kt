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

package com.itsaky.androidide.activities.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.itsaky.androidide.app.BaseIDEActivity
import com.itsaky.androidide.common.databinding.ActivityHelpBinding
import com.itsaky.androidide.documentation.DocumentationRequestInterceptor
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.DeviceFormFactorUtils
import com.itsaky.androidide.utils.UrlManager
import com.itsaky.androidide.utils.applyMultiWindowFlags
import com.itsaky.androidide.utils.isSystemInDarkMode
import org.adfa.constants.CONTENT_KEY
import org.adfa.constants.CONTENT_TITLE_KEY
import org.slf4j.LoggerFactory
import com.itsaky.androidide.common.R as CommonR

class HelpActivity : BaseIDEActivity() {
	companion object {
		private val EXTERNAL_SCHEMES = listOf("mailto:", "tel:", "sms:")
		private const val MULTI_WINDOW_URI = "cogo-help://tooltip/active-window"

		fun launch(
			context: Context,
			url: String,
			title: String,
		) {
			val intent =
				Intent(context, HelpActivity::class.java)
					.apply {
						putExtra(CONTENT_KEY, url)
						putExtra(CONTENT_TITLE_KEY, title)

						if (DeviceFormFactorUtils.getCurrent(context).isLargeScreenLike) {
							data = MULTI_WINDOW_URI.toUri()
						}
					}.applyMultiWindowFlags(context)
			context.startActivity(intent)
		}
	}

	private val log = LoggerFactory.getLogger(HelpActivity::class.java)

	// ADFA-5176: answers documentation requests from the database in-process, so loading a page
	// no longer opens a TCP connection per asset to the local web server.
	//
	// Lazy, not an initializer: forcing it here runs during Activity construction, before
	// onCreate, on the main thread -- which both stats external storage for the sentinel under a
	// StrictMode policy that reports it, and dies before this screen exists if the shared
	// interceptor cannot be built. Touched from shouldInterceptRequest instead, on a WebView
	// thread, the way FAQActivity does it. An explicit Lazy, so onPageFinished (main thread) can
	// log without being the first touch.
	private val documentationLazy = lazy { DocumentationRequestInterceptor.shared }
	private val documentation by documentationLazy

	// Wall-clock start of the page currently loading, for the ADFA-5176 measurement.
	// elapsedRealtime, not currentTimeMillis: an NTP correction or a user clock change between
	// onPageStarted and onPageFinished would otherwise report a negative or absurd duration.
	private var pageLoadStartMillis = 0L

	@Suppress("ktlint:standard:backing-property-naming")
	private var _binding: ActivityHelpBinding? = null
	private val binding: ActivityHelpBinding
		get() =
			checkNotNull(_binding) {
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
			toolbar.setNavigationOnClickListener { handleBackNavigation() }

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
			webView.webViewClient =
				object : WebViewClient() {
					override fun shouldInterceptRequest(
						view: android.webkit.WebView,
						request: android.webkit.WebResourceRequest,
					): android.webkit.WebResourceResponse? {
						val intercepted = documentation?.intercept(request)
						return intercepted ?: super.shouldInterceptRequest(view, request)
					}

					override fun onPageStarted(
						view: android.webkit.WebView?,
						url: String?,
						favicon: android.graphics.Bitmap?,
					) {
						super.onPageStarted(view, url, favicon)
						pageLoadStartMillis = SystemClock.elapsedRealtime()
					}

					override fun onPageFinished(
						view: android.webkit.WebView?,
						url: String?,
					) {
						super.onPageFinished(view, url)
						invalidateOptionsMenu()

						if (pageLoadStartMillis != 0L) {
							// The summary's counters are process-cumulative, so they are labelled as
							// such rather than read as this page's -- the tenth page in a session
							// would otherwise report the whole session's bytes as its own.
							// Only read the interceptor if some request already forced it: this
							// runs on the main thread, and being the first touch would build the
							// interceptor (and stat external storage) here.
							val summary =
								if (documentationLazy.isInitialized()) {
									documentation?.servedSummary()
								} else {
									"interceptor not yet used"
								}
							log.info(
								"Loaded '{}' in {} ms; in-process totals so far: {}.",
								url,
								SystemClock.elapsedRealtime() - pageLoadStartMillis,
								summary,
							)
							pageLoadStartMillis = 0L
						}
					}

					override fun doUpdateVisitedHistory(
						view: android.webkit.WebView?,
						url: String?,
						isReload: Boolean,
					) {
						super.doUpdateVisitedHistory(view, url, isReload)
						invalidateOptionsMenu()
					}

					override fun shouldOverrideUrlLoading(
						view: android.webkit.WebView?,
						url: String?,
					): Boolean = handleUrlLoading(view, url)

					override fun onReceivedError(
						view: android.webkit.WebView?,
						errorCode: Int,
						description: String?,
						failingUrl: String?,
					) {
						super.onReceivedError(view, errorCode, description, failingUrl)
						view?.loadData(
							"""
							<html><body>
							<h3>Error Loading Content</h3>
							<p>Unable to load: $failingUrl</p>
							<p>Error: $description</p>
							</body></html>
							""".trimIndent(),
							"text/html",
							"UTF-8",
						)
					}
				}

			// The page itself is loaded by updateUIFromIntent below -- the one place that does it,
			// since onNewIntent needs the same path. Loading it here as well made every open pay
			// two full reads: decode, render and serve the same row twice.
		}

		// Set up back navigation callback for system back button
		onBackPressedDispatcher.addCallback(
			this,
			object : OnBackPressedCallback(true) {
				override fun handleOnBackPressed() {
					handleBackNavigation()
				}
			},
		)
		updateUIFromIntent(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		updateUIFromIntent(intent)
	}

	private fun updateUIFromIntent(currentIntent: Intent) {
		val pageTitle = currentIntent.getStringExtra(CONTENT_TITLE_KEY)
		supportActionBar?.title = pageTitle ?: getString(R.string.help)

		currentIntent.getStringExtra(CONTENT_KEY)?.let { url ->
			binding.webView.loadUrl(url)
		}
	}

	private fun handleUrlLoading(
		view: android.webkit.WebView?,
		url: String?,
	): Boolean {
		url ?: return false
		return when {
			EXTERNAL_SCHEMES.any { url.startsWith(it) } -> {
				UrlManager.openUrl(url, context = this)
				true
			}

			url.startsWith("http://localhost:6174/") -> {
				view?.loadUrl(url)
				true
			}

			else -> {
				false
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(CommonR.menu.menu_help, menu)
		return true
	}

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		menu.findItem(CommonR.id.action_close_help)?.isVisible =
			_binding != null && binding.webView.canGoBack()
		return super.onPrepareOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean =
		when (item.itemId) {
			CommonR.id.action_close_help -> {
				finish()
				true
			}

			else -> {
				super.onOptionsItemSelected(item)
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
