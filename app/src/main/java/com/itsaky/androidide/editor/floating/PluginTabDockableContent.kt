

package com.itsaky.androidide.editor.floating

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentFactory
import com.itsaky.androidide.R
import com.itsaky.androidide.floating.fragment.OverlayFragmentHost
import com.itsaky.androidide.floating.model.ChromeControl
import com.itsaky.androidide.floating.model.DockableContent
import com.itsaky.androidide.floating.window.FloatingWindowHost
import com.itsaky.androidide.plugins.manager.fragment.PluginFragmentFactory
import com.itsaky.androidide.plugins.manager.ui.PluginEditorTabManager

/**
 * Adapts a plugin editor tab (an [com.itsaky.androidide.plugins.extensions.EditorTabExtension]
 * Fragment) to [DockableContent]. The Fragment is re-instantiated through the plugin's factory and
 * hosted in an [OverlayFragmentHost] (a FragmentManager with no Activity), so it can run over other
 * apps. A [PluginFragmentFactory] is installed so the plugin's classloader resolves on recreation.
 */
class PluginTabDockableContent(
	val tabId: String,
	override val title: String,
) : DockableContent {
	override val id: String = "plugin:$tabId"

	override val onChromeControlLongPress: (ChromeControl, View) -> Unit =
		ChromeControlTooltips.handler

	private var fragmentHost: OverlayFragmentHost? = null

	override fun onCreateView(
		context: Context,
		host: FloatingWindowHost,
	): View =
		try {
			val overlayHost = OverlayFragmentHost(context, host, PluginFragmentFactory(FragmentFactory()))
			fragmentHost = overlayHost
			overlayHost.start()
			val fragment = PluginEditorTabManager.getInstance().newTabFragment(tabId)
			if (fragment == null) {
				overlayHost.destroy()
				fragmentHost = null
				errorView(context)
			} else {
				overlayHost.setFragment(fragment)
				overlayHost.view
			}
		} catch (t: Throwable) {
			Log.e(TAG, "Plugin tab '$tabId' failed to load in a floating window", t)
			runCatching { fragmentHost?.destroy() }
			fragmentHost = null
			errorView(context)
		}

	override fun onDestroyView() {
		runCatching { fragmentHost?.destroy() }
		fragmentHost = null
	}

	private fun errorView(context: Context): View =
		TextView(context).apply {
			text = context.getString(R.string.msg_floating_window_open_failed, title)
			val pad = (16 * context.resources.displayMetrics.density).toInt()
			setPadding(pad, pad, pad, pad)
		}

	private companion object {
		private const val TAG = "PluginTabDockableContent"
	}
}
