package com.itsaky.androidide.activities

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import com.itsaky.androidide.app.IDEActivity
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.plugins.manager.fragment.PluginFragmentFactory
import com.itsaky.androidide.plugins.services.IdeUIService

class PluginScreenActivity : IDEActivity() {

    private var pluginId: String? = null
    private var fragmentClassName: String? = null
    private var containerId: Int = View.NO_ID

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

        val classLoader = IDEApplication.getPluginManager()
            ?.getClassLoaderForPluginId(pluginId)

        if (classLoader == null) {
            super.onCreate(savedInstanceState)
            finish()
            return
        }

        registerPluginFragmentFactory(
            pluginId = pluginId,
            classLoader = classLoader,
            fragmentClassName = fragmentClassName
        )

        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            loadPluginFragment(classLoader, fragmentClassName)
        }
    }

    override fun bindLayout(): View {
        return FrameLayout(this).apply {
            id = View.generateViewId()
            containerId = id
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun registerPluginFragmentFactory(
        pluginId: String,
        classLoader: ClassLoader,
        fragmentClassName: String
    ) {
        supportFragmentManager.fragmentFactory =
            PluginFragmentFactory(supportFragmentManager.fragmentFactory)

        PluginFragmentFactory.registerPluginClassLoader(
            pluginId = pluginId,
            classLoader = classLoader,
            fragmentClassNames = listOf(fragmentClassName)
        )
    }

    private fun loadPluginFragment(
        classLoader: ClassLoader,
        fragmentClassName: String
    ) {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            fragmentClassName
        )

        supportFragmentManager.beginTransaction()
            .replace(containerId, fragment, TAG_PLUGIN_SCREEN)
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
        private const val TAG_PLUGIN_SCREEN = "plugin_screen"
    }
}
