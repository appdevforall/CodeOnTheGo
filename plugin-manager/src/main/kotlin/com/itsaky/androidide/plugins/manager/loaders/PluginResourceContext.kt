package com.itsaky.androidide.plugins.manager.loaders

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.Resources.Theme
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

class PluginResourceContext(
    baseContext: Context,
    private val pluginResources: Resources,
    private val pluginPackageInfo: PackageInfo? = null,
    private val pluginClassLoader: ClassLoader? = null
) : ContextThemeWrapper(baseContext, 0) {

    companion object {
        private const val TAG = "PluginResourceContext"
        private val ADD_ASSET_PATH_METHOD = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
    }

    private var inflater: LayoutInflater? = null
    private var pluginTheme: Theme? = null
    private var lastActivityTheme: Theme? = null
    private var addedToAssetManager: AssetManager? = null
    private var usesCustomPackageId = false

    init {
        usesCustomPackageId = detectCustomPackageId()
    }

    private fun detectCustomPackageId(): Boolean {
        val cl = pluginClassLoader ?: return false
        val pkg = pluginPackageInfo?.packageName ?: return false
        try {
            val rClass = cl.loadClass("$pkg.R")
            for (inner in rClass.declaredClasses) {
                for (field in inner.declaredFields) {
                    if (field.type == Int::class.javaPrimitiveType) {
                        val id = field.getInt(null)
                        if (id != 0) return (id ushr 24) != 0x7F
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect custom package ID for $pkg", e)
        }
        return false
    }

    private fun ensurePluginPathAdded(assets: AssetManager) {
        if (addedToAssetManager === assets) return
        val pluginSourceDir = pluginPackageInfo?.applicationInfo?.sourceDir ?: return
        try {
            ADD_ASSET_PATH_METHOD.invoke(assets, pluginSourceDir)
            addedToAssetManager = assets
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add plugin asset path", e)
        }
    }

    override fun getResources(): Resources {
        if (!usesCustomPackageId) return pluginResources

        val actCtx = PluginFragmentHelper.getCurrentActivityContext()
        if (actCtx != null) {
            ensurePluginPathAdded(actCtx.resources.assets)
            return actCtx.resources
        }
        return pluginResources
    }

    override fun getAssets(): AssetManager = getResources().assets

    override fun getTheme(): Theme {
        if (!usesCustomPackageId) return baseContext.theme

        val actCtx = PluginFragmentHelper.getCurrentActivityContext()
        val actTheme = PluginFragmentHelper.getCurrentActivityTheme()
        if (actCtx != null && actTheme != null) {
            ensurePluginPathAdded(actCtx.resources.assets)
            if (pluginTheme == null || lastActivityTheme !== actTheme) {
                lastActivityTheme = actTheme
                inflater = null

                val pluginThemeResId = actCtx.resources.getIdentifier(
                    "PluginTheme", "style", pluginPackageInfo?.packageName
                )

                pluginTheme = actCtx.resources.newTheme().apply {
                    setTo(actTheme)
                    if (pluginThemeResId != 0) {
                        applyStyle(pluginThemeResId, true)
                    }
                }
            }
        }
        return pluginTheme ?: baseContext.theme
    }

    override fun getClassLoader(): ClassLoader {
        return pluginClassLoader ?: baseContext.classLoader
    }

    override fun getPackageName(): String {
        return pluginPackageInfo?.packageName ?: super.getPackageName()
    }

    override fun getApplicationInfo(): ApplicationInfo {
        return pluginPackageInfo?.applicationInfo ?: super.getApplicationInfo()
    }

    override fun getSystemService(name: String): Any? {
        if (Context.LAYOUT_INFLATER_SERVICE == name) {
            if (inflater == null) {
                inflater = LayoutInflater.from(baseContext).cloneInContext(this)
            }
            return inflater
        }
        return super.getSystemService(name)
    }

    fun getPluginPackageInfo(): PackageInfo? = pluginPackageInfo

    fun getResourceId(name: String, type: String): Int {
        return getResources().getIdentifier(name, type, packageName)
    }

    fun inflateLayout(layoutResId: Int, root: android.view.ViewGroup? = null, attachToRoot: Boolean = false): android.view.View {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        return inflater.inflate(layoutResId, root, attachToRoot)
    }
}
