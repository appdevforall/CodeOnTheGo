package com.itsaky.androidide.plugins.manager.loaders

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.Resources.Theme
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View

class PluginResourceContext(
    baseContext: Context,
    pluginResources: Resources,
    private val pluginPackageInfo: PackageInfo? = null
) : ContextThemeWrapper(baseContext, 0) {

    private var pluginResources: Resources = pluginResources
    private var inflater: LayoutInflater? = null
    private var lastNightMode: Int = -1
    private var pluginTheme: Theme? = null

    override fun getResources(): Resources {
        return pluginResources
    }

    override fun getAssets(): AssetManager {
        return pluginResources.assets
    }

    private fun recreatePluginResources(newConfig: Configuration) {
        val sourceDir = pluginPackageInfo?.applicationInfo?.sourceDir ?: return
        @Suppress("DEPRECATION")
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        addAssetPath.invoke(assetManager, sourceDir)
        @Suppress("DEPRECATION")
        pluginResources = Resources(assetManager, baseContext.resources.displayMetrics, newConfig)
    }

    override fun getTheme(): Theme {
        val currentNightMode = baseContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        if (currentNightMode != lastNightMode || pluginTheme == null) {
            lastNightMode = currentNightMode
            recreatePluginResources(baseContext.resources.configuration)
            inflater = null

            val pluginThemeResId = pluginResources.getIdentifier(
                "PluginTheme",
                "style",
                pluginPackageInfo?.packageName
            )

            val themeResId = if (pluginThemeResId != 0) {
                pluginThemeResId
            } else if (currentNightMode == Configuration.UI_MODE_NIGHT_YES) {
                android.R.style.Theme_Material
            } else {
                android.R.style.Theme_Material_Light
            }

            pluginTheme = pluginResources.newTheme().apply {
                applyStyle(themeResId, true)
            }
        }
        return pluginTheme!!
    }

    override fun getClassLoader(): ClassLoader {
        // Use the base context's class loader to ensure Android framework classes can be loaded
        // This is critical for LayoutInflater to find system widget classes
        return baseContext.classLoader
    }

    override fun getPackageName(): String {
        // Return plugin's package name if available
        return pluginPackageInfo?.packageName ?: super.getPackageName()
    }

    override fun getApplicationInfo(): ApplicationInfo {
        // Return plugin's application info if available
        return pluginPackageInfo?.applicationInfo ?: super.getApplicationInfo()
    }

    override fun getSystemService(name: String): Any? {
        if (Context.LAYOUT_INFLATER_SERVICE == name) {
            if (inflater == null) {
                // Create a custom LayoutInflater that can properly handle system widgets
                val baseInflater = LayoutInflater.from(baseContext)
                inflater = object : LayoutInflater(baseInflater, this) {
                    override fun cloneInContext(newContext: Context): LayoutInflater {
                        return getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                    }

                    override fun onCreateView(name: String, attrs: AttributeSet): View? {
                        if (name.indexOf('.') == -1) {
                            try {
                                val view = createView(name, "android.widget.", attrs)
                                return view
                            } catch (e: ClassNotFoundException) {
                                try {
                                    val view = createView(name, "android.view.", attrs)
                                    return view
                                } catch (e2: ClassNotFoundException) {
                                    try {
                                        val view = createView(name, "android.webkit.", attrs)
                                        return view
                                    } catch (e3: ClassNotFoundException) {
                                        // Let parent handle it
                                    }
                                }
                            }
                        }
                        return super.onCreateView(name, attrs)
                    }
                }
            }
            return inflater
        }
        return super.getSystemService(name)
    }

    /**
     * Get the plugin's package info
     */
    fun getPluginPackageInfo(): PackageInfo? {
        return pluginPackageInfo
    }

    /**
     * Helper to get resource ID by name
     */
    fun getResourceId(name: String, type: String): Int {
        return pluginResources.getIdentifier(name, type, packageName)
    }

    /**
     * Helper to inflate layout
     */
    fun inflateLayout(layoutResId: Int, root: android.view.ViewGroup? = null, attachToRoot: Boolean = false): android.view.View {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        return inflater.inflate(layoutResId, root, attachToRoot)
    }
}