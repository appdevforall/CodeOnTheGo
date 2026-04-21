package com.itsaky.androidide.plugins.manager.loaders

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.Resources.Theme
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.manager.core.PluginManager
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class PluginResourceContext(
    baseContext: Context,
    private val pluginId: String,
    private var pluginResources: Resources,
    private val pluginPackageInfo: PackageInfo? = null,
    private val pluginClassLoader: ClassLoader? = null
) : ContextThemeWrapper(baseContext, 0) {

    companion object {
        private const val TAG = "PluginResourceContext"
        private const val PLUGIN_THEME_NAME = "PluginTheme"
        private val ADD_ASSET_PATH_METHOD = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
    }

    private var inflater: LayoutInflater? = null
    private var pluginTheme: Theme? = null
    private var lastActivityTheme: Theme? = null
    private var lastNightMode: Int = -1
    private var usesCustomPackageId = false
    private val pluginSourceDir: String? = pluginPackageInfo?.applicationInfo?.sourceDir
    private val appThemeResId: Int = pluginPackageInfo?.applicationInfo?.theme ?: 0
    private var pluginThemeResId: Int = 0
    private var pluginThemeResIdResolved = false

    init {
        usesCustomPackageId = detectCustomPackageId()
    }

    private fun detectCustomPackageId(): Boolean {
        val cl = pluginClassLoader ?: return false
        val pkg = pluginPackageInfo?.packageName ?: return false
        return try {
            val rClass = cl.loadClass("$pkg.R")
            rClass.declaredClasses.asSequence()
                .flatMap { it.declaredFields.asSequence() }
                .filter { it.type == Int::class.javaPrimitiveType }
                .map { it.getInt(null) }
                .filter { it != 0 }
                .any { (it ushr 24) != 0x7F }
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Failed to detect custom package ID for $pkg", e)
            false
        }
    }

    fun usesCustomPackageId(): Boolean = usesCustomPackageId

    private var recreatedAssetManager: AssetManager? = null

    private fun recreatePluginResources(newConfig: Configuration) {
        val sourceDir = pluginSourceDir ?: return
        val oldAssetManager = recreatedAssetManager
        @Suppress("DEPRECATION")
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        addAssetPathFallback(assetManager, sourceDir)
        @Suppress("DEPRECATION")
        pluginResources = Resources(assetManager, baseContext.resources.displayMetrics, newConfig)
        recreatedAssetManager = assetManager
        oldAssetManager?.close()
    }

    private var patchedResources: Resources? = null
    private var cachedLoader: ResourcesLoader? = null
    private var cachedProvider: ResourcesProvider? = null

    @Suppress("NewApi")
    private fun ensurePluginResourcesAdded(targetResources: Resources) {
        if (patchedResources === targetResources) return
        val sourceDir = pluginSourceDir ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (cachedLoader == null) {
                    ParcelFileDescriptor.open(File(sourceDir), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        cachedProvider = ResourcesProvider.loadFromApk(pfd)
                        cachedLoader = ResourcesLoader().also { it.addProvider(cachedProvider!!) }
                    }
                }
                targetResources.addLoaders(cachedLoader!!)
                patchedResources = targetResources
                Log.d(TAG, "Added plugin resources via ResourcesLoader for ${pluginPackageInfo?.packageName}")
            } catch (e: FileNotFoundException) {
                Log.e(TAG, "ResourcesLoader failed, falling back to addAssetPath", e)
                addAssetPathFallback(targetResources.assets, sourceDir)
                patchedResources = targetResources
            } catch (e: IOException) {
                Log.e(TAG, "ResourcesLoader failed, falling back to addAssetPath", e)
                addAssetPathFallback(targetResources.assets, sourceDir)
                patchedResources = targetResources
            }
        } else {
            addAssetPathFallback(targetResources.assets, sourceDir)
            patchedResources = targetResources
        }
    }

    private fun addAssetPathFallback(assets: AssetManager, pluginSourceDir: String) {
        try {
            ADD_ASSET_PATH_METHOD.invoke(assets, pluginSourceDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add plugin asset path", e)
        }
    }

    private fun resolveActivityContextAndTheme(): Pair<Context, Theme>? {
        val fragmentCtx = PluginFragmentHelper.getCurrentActivityContext(pluginId)
        val fragmentTheme = PluginFragmentHelper.getCurrentActivityTheme(pluginId)
        if (fragmentCtx != null && fragmentTheme != null) {
            return fragmentCtx to fragmentTheme
        }
        val activity = PluginManager.getInstance()?.getCurrentActivity() ?: return null
        return activity to activity.theme
    }

    override fun getResources(): Resources {
        if (!usesCustomPackageId) return pluginResources

        val (actCtx, _) = resolveActivityContextAndTheme() ?: return pluginResources
        ensurePluginResourcesAdded(actCtx.resources)
        return actCtx.resources
    }

    override fun getAssets(): AssetManager = getResources().assets

    override fun getTheme(): Theme {
        if (!usesCustomPackageId) {
            val currentNightMode = baseContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (pluginTheme == null || lastNightMode != currentNightMode) {
                lastNightMode = currentNightMode
                inflater = null
                recreatePluginResources(baseContext.resources.configuration)
                if (!pluginThemeResIdResolved) {
                    pluginThemeResId = pluginResources.getIdentifier(
                        PLUGIN_THEME_NAME, "style", pluginPackageInfo?.packageName
                    )
                    pluginThemeResIdResolved = true
                }
                val themeResId = when {
                    pluginThemeResId != 0 -> pluginThemeResId
                    appThemeResId != 0 -> appThemeResId
                    currentNightMode == Configuration.UI_MODE_NIGHT_YES -> android.R.style.Theme_Material
                    else -> android.R.style.Theme_Material_Light
                }
                pluginTheme = pluginResources.newTheme().apply {
                    applyStyle(themeResId, true)
                }
            }
            return pluginTheme!!
        }

        val (actCtx, actTheme) = resolveActivityContextAndTheme() ?: return pluginTheme ?: baseContext.theme
        ensurePluginResourcesAdded(actCtx.resources)
        if (pluginTheme == null || lastActivityTheme !== actTheme) {
            lastActivityTheme = actTheme
            inflater = null

            if (!pluginThemeResIdResolved) {
                pluginThemeResId = actCtx.resources.getIdentifier(
                    PLUGIN_THEME_NAME, "style", pluginPackageInfo?.packageName
                )
                pluginThemeResIdResolved = true
            }

            pluginTheme = actCtx.resources.newTheme().apply {
                setTo(actTheme)

                if (appThemeResId != 0) {
                    applyStyle(appThemeResId, false)
                }

                if (pluginThemeResId != 0) {
                    applyStyle(pluginThemeResId, true)
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
                if (!usesCustomPackageId) {
                    val baseInflater = LayoutInflater.from(baseContext)
                    inflater = object : LayoutInflater(baseInflater, this@PluginResourceContext) {
                        override fun cloneInContext(newContext: Context): LayoutInflater {
                            return this@PluginResourceContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                        }

                        override fun onCreateView(viewName: String, attrs: AttributeSet): View? {
                            if (viewName.indexOf('.') == -1) {
                                for (prefix in arrayOf("android.widget.", "android.view.", "android.webkit.")) {
                                    try { return createView(viewName, prefix, attrs) } catch (_: ClassNotFoundException) {}
                                }
                            }
                            return super.onCreateView(viewName, attrs)
                        }
                    }
                } else {
                    inflater = LayoutInflater.from(baseContext).cloneInContext(this)
                }
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