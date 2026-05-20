package com.itsaky.androidide.plugins.manager.core

import com.itsaky.androidide.plugins.PluginLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PluginCrashTracker(
    private val context: Context,
    private val logger: PluginLogger
) {

    private val crashCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val crashCountsFile get() = File(context.filesDir, "plugin_crash_counts.properties")

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistMutex = Mutex()

    init {
        loadCrashCounts()
    }

    fun recordCrash(pluginId: String): Int {
        val count = crashCounts
            .getOrPut(pluginId) { AtomicInteger(0) }
            .incrementAndGet()
        persistCrashCounts()
        logger.warn("Plugin crash recorded: $pluginId (count: $count/$MAX_CRASH_COUNT)")
        return count
    }

    fun shouldDisable(pluginId: String): Boolean {
        return getCrashCount(pluginId) >= MAX_CRASH_COUNT
    }

    fun getCrashCount(pluginId: String): Int {
        return crashCounts[pluginId]?.get() ?: 0
    }

    fun resetCrashCount(pluginId: String) {
        crashCounts.remove(pluginId)
        persistCrashCounts()
        logger.info("Reset crash count for plugin: $pluginId")
    }

    fun removeCrashCount(pluginId: String) {
        crashCounts.remove(pluginId)
        persistCrashCounts()
    }

    fun findPluginForStackTrace(
        throwable: Throwable,
        pluginIds: Set<String>,
        classLoaderProvider: (String) -> ClassLoader?
    ): String? {
        val classLoaders = pluginIds.mapNotNull { id ->
            classLoaderProvider(id)?.let { id to it }
        }
        if (classLoaders.isEmpty()) return null

        var current: Throwable? = throwable
        while (current != null) {
            for (frame in current.stackTrace) {
                for ((pluginId, classLoader) in classLoaders) {
                    val loaded = runCatching {
                        Class.forName(frame.className, false, classLoader)
                    }.getOrNull() ?: continue

                    if (loaded.classLoader === classLoader) {
                        logger.info("Identified plugin $pluginId from stack trace class: ${frame.className}")
                        return pluginId
                    }
                }
            }
            current = current.cause
        }

        return null
    }

    private fun loadCrashCounts() {
        runCatching {
            val file = crashCountsFile
            if (!file.exists()) return

            val properties = java.util.Properties()
            file.inputStream().use { properties.load(it) }

            properties.forEach { key, value ->
                val pluginId = key as String
                val count = (value as String).toIntOrNull() ?: 0
                if (count > 0) {
                    crashCounts[pluginId] = AtomicInteger(count)
                }
            }
        }.onFailure { e ->
            logger.error("Failed to load plugin crash counts", e)
        }
    }

    private fun persistCrashCounts() {
        persistScope.launch {
            persistMutex.withLock {
                runCatching {
                    val properties = java.util.Properties()
                    crashCounts.forEach { (pluginId, count) ->
                        val value = count.get()
                        if (value > 0) {
                            properties.setProperty(pluginId, value.toString())
                        }
                    }
                    crashCountsFile.outputStream().use { output ->
                        properties.store(output, "Plugin crash counts")
                    }
                }.onFailure { e ->
                    logger.error("Failed to persist plugin crash counts", e)
                }
            }
        }
    }

    companion object {
        private const val MAX_CRASH_COUNT = 3
    }
}
