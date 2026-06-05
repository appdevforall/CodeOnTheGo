package com.itsaky.androidide.compose.preview.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Method

class ProjectResourceContextFactory(context: Context) {

    private val appContext = context.applicationContext

    private var cacheKey: String? = null
    private var cachedAssets: AssetManager? = null
    private val retainedAssets = mutableListOf<AssetManager>()

    @Synchronized
    fun contextFor(apk: File?, configuration: Configuration): Context {
        val assets = assetsFor(apk)
            ?: return appContext.createConfigurationContext(configuration)

        @Suppress("DEPRECATION")
        val resources = Resources(assets, appContext.resources.displayMetrics, configuration)
        return object : ContextWrapper(appContext) {
            override fun getAssets(): AssetManager = assets
            override fun getResources(): Resources = resources
        }
    }

    private fun assetsFor(apk: File?): AssetManager? {
        if (apk == null || !apk.exists()) {
            LOG.warn("Project APK unavailable; resources fall back to IDE context: {}",
                apk?.absolutePath ?: "null")
            return null
        }

        val key = "${apk.absolutePath}:${apk.lastModified()}"
        if (key == cacheKey && cachedAssets != null) {
            return cachedAssets
        }

        val addAssetPath = ADD_ASSET_PATH ?: return null

        return try {
            @Suppress("DEPRECATION")
            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val cookie = addAssetPath.invoke(assets, apk.absolutePath) as Int
            if (cookie == 0) {
                LOG.error("addAssetPath returned 0 for {}", apk.absolutePath)
                assets.close()
                return null
            }
            cachedAssets?.let { retainedAssets.add(it) }
            cachedAssets = assets
            cacheKey = key
            assets
        } catch (e: Throwable) {
            LOG.error("Failed to build project AssetManager from {}", apk.absolutePath, e)
            null
        }
    }

    @Synchronized
    fun release() {
        cachedAssets?.close()
        retainedAssets.forEach { it.close() }
        retainedAssets.clear()
        cachedAssets = null
        cacheKey = null
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ProjectResourceContextFactory::class.java)
        private val ADD_ASSET_PATH: Method? by lazy {
            try {
                AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            } catch (e: Throwable) {
                LOG.error("addAssetPath reflective lookup failed; project resources unavailable", e)
                null
            }
        }
    }
}
