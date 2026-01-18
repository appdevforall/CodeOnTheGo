package com.itsaky.androidide.plugins.manager.fragment

import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory

class PluginFragmentFactory(
    private val defaultFactory: FragmentFactory
) : FragmentFactory() {

    companion object {
        private const val TAG = "PluginFragmentFactory"

        private val pluginClassLoaders = mutableMapOf<String, ClassLoader>()

        @JvmStatic
        fun registerPluginClassLoader(pluginId: String, classLoader: ClassLoader, fragmentClassNames: List<String>) {
            fragmentClassNames.forEach { className ->
                pluginClassLoaders[className] = classLoader
                Log.d(TAG, "Registered classloader for fragment: $className (plugin: $pluginId)")
            }
        }

        @JvmStatic
        fun unregisterPluginClassLoader(pluginId: String, fragmentClassNames: List<String>) {
            fragmentClassNames.forEach { className ->
                pluginClassLoaders.remove(className)
                Log.d(TAG, "Unregistered classloader for fragment: $className (plugin: $pluginId)")
            }
        }

        @JvmStatic
        fun hasClassLoaderForFragment(className: String): Boolean {
            return pluginClassLoaders.containsKey(className)
        }

        @JvmStatic
        fun getClassLoaderForFragment(className: String): ClassLoader? {
            return pluginClassLoaders[className]
        }

        @JvmStatic
        fun clearAllClassLoaders() {
            pluginClassLoaders.clear()
            Log.d(TAG, "Cleared all plugin fragment classloaders")
        }
    }

    override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
        val pluginClassLoader = pluginClassLoaders[className]

        if (pluginClassLoader != null) {
            Log.d(TAG, "Instantiating plugin fragment with plugin classloader: $className")
            return try {
                val fragmentClass = pluginClassLoader.loadClass(className)
                val constructor = fragmentClass.getDeclaredConstructor()
                constructor.isAccessible = true
                constructor.newInstance() as Fragment
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate plugin fragment: $className", e)
                throw e
            }
        }

        Log.d(TAG, "Using default factory for fragment: $className")
        return defaultFactory.instantiate(classLoader, className)
    }
}
