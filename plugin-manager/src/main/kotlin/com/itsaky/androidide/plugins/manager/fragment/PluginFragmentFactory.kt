package com.itsaky.androidide.plugins.manager.fragment

import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import java.util.concurrent.ConcurrentHashMap

class PluginFragmentFactory(
    private val defaultFactory: FragmentFactory
) : FragmentFactory() {

    companion object {
        private const val TAG = "PluginFragmentFactory"

        private val pluginClassLoaders = ConcurrentHashMap<String, ClassLoader>()
        private val pluginFragmentClasses = ConcurrentHashMap<String, MutableSet<String>>()

        @JvmStatic
        fun registerPluginClassLoader(pluginId: String, classLoader: ClassLoader, fragmentClassNames: List<String>) {
            val fragmentSet = pluginFragmentClasses.computeIfAbsent(pluginId) {
                ConcurrentHashMap.newKeySet()
            }
            fragmentClassNames.forEach { className ->
                pluginClassLoaders[className] = classLoader
                fragmentSet.add(className)
                Log.d(TAG, "Registered classloader for fragment: $className (plugin: $pluginId)")
            }
        }

        @JvmStatic
        fun unregisterPluginClassLoader(pluginId: String, fragmentClassNames: List<String>) {
            val fragmentSet = pluginFragmentClasses[pluginId]
            fragmentClassNames.forEach { className ->
                pluginClassLoaders.remove(className)
                fragmentSet?.remove(className)
                Log.d(TAG, "Unregistered classloader for fragment: $className (plugin: $pluginId)")
            }
        }

        @JvmStatic
        fun unregisterAllClassLoadersForPlugin(pluginId: String) {
            val fragmentClassNames = pluginFragmentClasses.remove(pluginId) ?: return
            fragmentClassNames.forEach { className ->
                pluginClassLoaders.remove(className)
                Log.d(TAG, "Unregistered classloader for fragment: $className (plugin: $pluginId)")
            }
            Log.d(TAG, "Unregistered all classloaders for plugin: $pluginId (${fragmentClassNames.size} fragments)")
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
            pluginFragmentClasses.clear()
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
