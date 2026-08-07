package com.itsaky.androidide.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.itsaky.androidide.R
import com.itsaky.androidide.app.IDEActivity
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.plugins.manager.fragment.PluginFragmentFactory
import com.itsaky.androidide.plugins.services.IdeUIService

class PluginScreenActivity : IDEActivity() {
	private var pluginId: String? = null
	private var fragmentClassName: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		pluginId = intent.getStringExtra(IdeUIService.EXTRA_PLUGIN_ID)
		fragmentClassName = intent.getStringExtra(IdeUIService.EXTRA_FRAGMENT_CLASS_NAME)

		val pluginId = pluginId
		val fragmentClassName = fragmentClassName

		if (pluginId.isNullOrBlank() || fragmentClassName.isNullOrBlank()) {
			super.onCreate(savedInstanceState)
			finish()
			return
		}

		val classLoader =
			IDEApplication
				.getPluginManager()
				?.getClassLoaderForPluginId(pluginId)

		if (classLoader == null) {
			super.onCreate(savedInstanceState)
			finish()
			return
		}

		registerPluginFragmentFactory(
			pluginId = pluginId,
			classLoader = classLoader,
			fragmentClassName = fragmentClassName,
		)

		super.onCreate(savedInstanceState)

		if (savedInstanceState == null) {
			loadPluginFragment(classLoader, fragmentClassName)
		}
	}

	override fun bindLayout(): View =
		FrameLayout(this).apply {
			id = R.id.plugin_screen_container
			layoutParams =
				FrameLayout.LayoutParams(
					FrameLayout.LayoutParams.MATCH_PARENT,
					FrameLayout.LayoutParams.MATCH_PARENT,
				)
		}

	private fun registerPluginFragmentFactory(
		pluginId: String,
		classLoader: ClassLoader,
		fragmentClassName: String,
	) {
		supportFragmentManager.fragmentFactory =
			PluginFragmentFactory(supportFragmentManager.fragmentFactory)

		PluginFragmentFactory.registerPluginClassLoader(
			pluginId,
			classLoader,
			listOf(fragmentClassName),
		)
	}

	private fun loadPluginFragment(
		classLoader: ClassLoader,
		fragmentClassName: String,
	) {
		val fragment =
			runCatching {
				supportFragmentManager.fragmentFactory.instantiate(
					classLoader,
					fragmentClassName,
				)
			}.getOrElse { error ->
				Log.e(TAG, "Failed to instantiate plugin fragment: $fragmentClassName", error)
				finish()
				return
			}

		supportFragmentManager
			.beginTransaction()
			.replace(R.id.plugin_screen_container, fragment, TAG_PLUGIN_SCREEN)
			.commit()
	}

	override fun onDestroy() {
		val pluginId = pluginId
		val fragmentClassName = fragmentClassName

		if (!pluginId.isNullOrBlank() && !fragmentClassName.isNullOrBlank()) {
			PluginFragmentFactory.unregisterPluginClassLoader(pluginId, listOf(fragmentClassName))
		}

		super.onDestroy()
	}

	companion object {
		private const val TAG = "PluginScreenActivity"
		private const val TAG_PLUGIN_SCREEN = "plugin_screen"

		/**
		 * The intent that mounts [fragmentClassName] of [pluginId] on this screen - the same one
		 * `IdeUIServiceImpl.openPluginScreen` builds, so both entry points stay in step. Action-based
		 * rather than explicit-component to match the manifest intent-filter; [setPackage] keeps it
		 * inside the app.
		 *
		 * No reuse flags: every entry is its own screen, and the extras are only read in [onCreate],
		 * so a reused instance would keep showing the previous plugin's fragment.
		 */
		fun newIntent(
			context: Context,
			pluginId: String,
			fragmentClassName: String,
			title: String? = null,
		): Intent =
			Intent(IdeUIService.ACTION_OPEN_PLUGIN_SCREEN).apply {
				setPackage(context.packageName)
				putExtra(IdeUIService.EXTRA_PLUGIN_ID, pluginId)
				putExtra(IdeUIService.EXTRA_FRAGMENT_CLASS_NAME, fragmentClassName)
				putExtra(IdeUIService.EXTRA_TITLE, title)
			}
	}
}
