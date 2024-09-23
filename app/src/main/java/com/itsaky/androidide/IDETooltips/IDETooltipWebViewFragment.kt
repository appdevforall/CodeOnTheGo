package com.itsaky.androidide.IDETooltips

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.itsaky.androidide.R


class IDETooltipWebviewFragment : Fragment() {
    private lateinit var webView: WebView


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.ide_tooltip_fragment_webview, container, false)

        // Initialize the WebView
        webView = view.findViewById(R.id.webView)

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
                        isEnabled = false // Disable this callback to let the default back press behavior occur
                    }
                }
            })

        // Set up WebChromeClient to support JavaScript
        webView.webChromeClient = WebChromeClient()

        // Enable JavaScript if needed
        webView.settings.javaScriptEnabled = true

        // Load the HTML file from the assets folder
        webView.loadUrl("file:///android_asset/index.html")
//        webView.loadUrl("file:///android_asset/AnchorTestLink.html")

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up the WebView in Fragment
        webView.clearHistory()
        webView.loadUrl("about:blank")
        webView.destroy()
    }
}
