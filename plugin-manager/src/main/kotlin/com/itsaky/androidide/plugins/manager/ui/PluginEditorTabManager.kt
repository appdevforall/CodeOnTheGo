package com.itsaky.androidide.plugins.manager.ui

import android.util.Log
import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.plugins.manager.fragment.PluginFragmentFactory
import com.itsaky.androidide.eventbus.events.plugin.PluginCrashedEvent
import org.greenrobot.eventbus.EventBus
import org.slf4j.LoggerFactory

/**
 * Manages plugin-contributed editor tabs for integration with the main editor tab system.
 */
class PluginEditorTabManager {

    companion object {
        private val logger = LoggerFactory.getLogger(PluginEditorTabManager::class.java)

        @Volatile
        private var instance: PluginEditorTabManager? = null

        fun getInstance(): PluginEditorTabManager {
            return instance ?: synchronized(this) {
                instance ?: PluginEditorTabManager().also { instance = it }
            }
        }
    }

    private val pluginTabs = mutableMapOf<String, PluginTabInfo>()
    private val tabFragments = mutableMapOf<String, Fragment>()
    private var tabSelectionListener: TabSelectionListener? = null
    private var pluginManagerRef: PluginManager? = null

    interface TabSelectionListener {
        fun onTabSelected(tabId: String, fragment: Fragment)
        fun onTabClosed(tabId: String)
    }

    private data class PluginTabInfo(
        val extension: EditorTabExtension,
        val tabItem: EditorTabItem
    )

    fun setTabSelectionListener(listener: TabSelectionListener?) {
        this.tabSelectionListener = listener
    }

    private fun resolvePluginId(extension: EditorTabExtension): String? {
        return pluginManagerRef?.getPluginIdForInstance(extension as IPlugin)
    }

    private fun handlePluginCrash(pluginId: String, context: String, error: Throwable) {
        logger.error("Plugin '{}' crashed in {}", pluginId, context, error)
        val pm = pluginManagerRef ?: return
        val result = pm.recordPluginCrash(pluginId)
        val wasDisabled = result is PluginManager.CrashResult.Disabled
        val crashCount = when (result) {
            is PluginManager.CrashResult.Recorded -> result.crashCount
            is PluginManager.CrashResult.Disabled -> pm.crashTracker.getCrashCount(pluginId)
        }
        runCatching {
            EventBus.getDefault().post(
                PluginCrashedEvent(pluginId, result.pluginName, crashCount, wasDisabled, Log.getStackTraceString(error))
            )
        }
    }

    /**
     * Load and register plugin editor tabs from all available plugins.
     */
    fun loadPluginTabs(pluginManager: PluginManager) {
        logger.debug("Loading plugin editor tabs...")
        pluginManagerRef = pluginManager

        val loadedPlugins = pluginManager.getAllPluginInstances()
        logger.debug("Found {} loaded plugins", loadedPlugins.size)

        val newPluginTabs = mutableMapOf<String, PluginTabInfo>()

        val editorTabExtensions = loadedPlugins.filterIsInstance<EditorTabExtension>()
        logger.debug("Found {} plugins with EditorTabExtension", editorTabExtensions.size)

        editorTabExtensions.forEach { plugin ->
            logger.debug("Processing plugin: {}", plugin.javaClass.name)
            val pluginId = resolvePluginId(plugin)

            val tabItems = try {
                plugin.getMainEditorTabs()
            } catch (e: Throwable) {
                if (pluginId != null) handlePluginCrash(pluginId, "getMainEditorTabs()", e)
                return@forEach
            }
            logger.debug("Plugin {} returned {} main editor tabs", plugin.javaClass.name, tabItems.size)

            val filteredTabItems = tabItems
                .filter { it.isEnabled && it.isVisible }
                .filter { !newPluginTabs.containsKey(it.id) }

            logger.debug("After filtering: {} tabs remain for plugin {}", filteredTabItems.size, plugin.javaClass.name)

            filteredTabItems.forEach { tabItem ->
                newPluginTabs[tabItem.id] = PluginTabInfo(plugin, tabItem)
                logger.info("Registered plugin editor tab: {} - {} from plugin {}", tabItem.id, tabItem.title, plugin.javaClass.name)
            }
        }

        synchronized(this) {
            pluginTabs.clear()
            pluginTabs.putAll(newPluginTabs)
        }

        logger.info("Loaded {} plugin editor tabs total", pluginTabs.size)
        logger.debug("Registered tab IDs: {}", pluginTabs.keys)
    }

    /**
     * Get all currently registered plugin editor tabs, sorted by order.
     */
    fun getAllPluginTabs(): List<EditorTabItem> {
        return synchronized(this) {
            pluginTabs.values
                .map { it.tabItem }
                .sortedBy { it.order }
        }
    }

    /**
     * Get a plugin tab by its ID.
     */
    fun getPluginTab(tabId: String): EditorTabItem? {
        return synchronized(this) {
            pluginTabs[tabId]?.tabItem
        }
    }

    /**
     * Check if a tab ID belongs to a plugin tab.
     */
    fun isPluginTab(tabId: String): Boolean {
        return synchronized(this) {
            pluginTabs.containsKey(tabId)
        }
    }

    fun getPluginIdForTab(tabId: String): String? {
        return synchronized(this) {
            val tabInfo = pluginTabs[tabId] ?: return null
            pluginManagerRef?.getPluginIdForInstance(tabInfo.extension)
        }
    }

    /**
     * Create or get the fragment for a plugin tab.
     */
    fun getOrCreateTabFragment(tabId: String): Fragment? {
        return synchronized(this) {
            tabFragments[tabId] ?: run {
                val tabInfo = pluginTabs[tabId] ?: return null
                val fragment = try {
                    tabInfo.tabItem.fragmentFactory()
                } catch (e: Throwable) {
                    val pluginId = resolvePluginId(tabInfo.extension)
                    if (pluginId != null) handlePluginCrash(pluginId, "fragmentFactory()", e)
                    return null
                }
                tabFragments[tabId] = fragment
                logger.debug("Created fragment for plugin tab: {}", tabId)

                registerFragmentClassLoader(tabInfo.extension, fragment)

                fragment
            }
        }
    }

    private fun registerFragmentClassLoader(extension: EditorTabExtension, fragment: Fragment) {
        val pluginManager = pluginManagerRef ?: run {
            logger.warn("PluginManager not available, cannot register fragment classloader")
            return
        }

        val classLoader = pluginManager.getClassLoaderForPlugin(extension)
        if (classLoader != null) {
            val fragmentClassName = fragment.javaClass.name
            val pluginId = pluginManager.getPluginIdForInstance(extension) ?: "unknown"
            PluginFragmentFactory.registerPluginClassLoader(
                pluginId,
                classLoader,
                listOf(fragmentClassName)
            )
            logger.info("Registered classloader for fragment {} from plugin {}", fragmentClassName, pluginId)
        } else {
            logger.warn("No classloader found for plugin extension: {}", extension.javaClass.name)
        }
    }

    /**
     * Handle tab selection event.
     */
    fun onTabSelected(tabId: String) {
        synchronized(this) {
            val tabInfo = pluginTabs[tabId] ?: return
            val fragment = tabFragments[tabId] ?: return

            try {
                tabInfo.extension.onEditorTabSelected(tabId, fragment)
            } catch (e: Throwable) {
                val pluginId = resolvePluginId(tabInfo.extension)
                if (pluginId != null) handlePluginCrash(pluginId, "onEditorTabSelected()", e)
                return
            }
            tabSelectionListener?.onTabSelected(tabId, fragment)
            logger.debug("Tab selection handled for plugin tab: {}", tabId)
        }
    }

    /**
     * Handle tab close event.
     */
    fun canCloseTab(tabId: String): Boolean {
        return synchronized(this) {
            val tabInfo = pluginTabs[tabId] ?: return true
            val canClose = try {
                tabInfo.extension.canCloseEditorTab(tabId)
            } catch (e: Throwable) {
                val pluginId = resolvePluginId(tabInfo.extension)
                if (pluginId != null) handlePluginCrash(pluginId, "canCloseEditorTab()", e)
                true
            }
            canClose && tabInfo.tabItem.isCloseable
        }
    }

    /**
     * Close a plugin tab.
     */
    fun closeTab(tabId: String) {
        synchronized(this) {
            val tabInfo = pluginTabs[tabId] ?: return

            try {
                tabInfo.extension.onEditorTabClosed(tabId)
            } catch (e: Throwable) {
                val pluginId = resolvePluginId(tabInfo.extension)
                if (pluginId != null) handlePluginCrash(pluginId, "onEditorTabClosed()", e)
            }
            tabFragments.remove(tabId)
            tabSelectionListener?.onTabClosed(tabId)
            logger.debug("Closed plugin tab: {}", tabId)
        }
    }

    /**
     * Get persistent plugin tabs that should be restored on app restart.
     */
    fun getPersistentTabIds(): List<String> {
        return synchronized(this) {
            pluginTabs.values
                .filter { it.tabItem.isPersistent }
                .map { it.tabItem.id }
        }
    }

    /**
     * Clear all plugin tabs and fragments.
     */
    fun clear() {
        synchronized(this) {
            logger.debug("Clearing all plugin editor tabs")
            pluginTabs.clear()
            tabFragments.clear()
        }
    }

    /**
     * Reload plugin tabs.
     */
    fun reloadPluginTabs(pluginManager: PluginManager) {
        clear()
        loadPluginTabs(pluginManager)
    }
}