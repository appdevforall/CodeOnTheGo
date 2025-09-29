package com.itsaky.androidide.plugins.manager.loaders

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.Resources.Theme
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View

/**
 * Context wrapper that provides plugin-specific resources
 */
class PluginResourceContext(
    baseContext: Context,
    private val pluginResources: Resources,
    private val pluginPackageInfo: PackageInfo? = null
) : ContextThemeWrapper(baseContext, android.R.style.Theme_Material_Light) {

    private var inflater: LayoutInflater? = null

    init {
        // Apply plugin's theme if it exists
        val themeResId = pluginResources.getIdentifier(
            "PluginTheme",
            "style",
            pluginPackageInfo?.packageName
        )
        if (themeResId != 0) {
            theme.applyStyle(themeResId, true)
        }
    }

    override fun getResources(): Resources {
        return pluginResources
    }

    override fun getAssets(): AssetManager {
        return pluginResources.assets
    }

    override fun getTheme(): Theme {
        // Return the theme from ContextThemeWrapper which is properly initialized
        return super.getTheme()
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