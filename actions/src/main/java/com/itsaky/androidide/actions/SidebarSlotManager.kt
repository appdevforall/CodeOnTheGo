
package com.itsaky.androidide.actions

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object SidebarSlotManager {

    const val MAX_NAVIGATION_RAIL_ITEMS = 12

    private val builtInItemCount = AtomicInteger(0)
    private val reservedPluginSlots = ConcurrentHashMap<String, Int>()

    fun setBuiltInItemCount(count: Int) {
        require(count in 0..MAX_NAVIGATION_RAIL_ITEMS) {
            "Built-in item count must be between 0 and $MAX_NAVIGATION_RAIL_ITEMS"
        }
        builtInItemCount.set(count)
    }

    fun getBuiltInItemCount(): Int = builtInItemCount.get()

    fun getReservedPluginSlotCount(): Int = reservedPluginSlots.values.sum()

    fun getTotalItemCount(): Int = builtInItemCount.get() + getReservedPluginSlotCount()

    fun getAvailableSlotsForPlugins(): Int =
        (MAX_NAVIGATION_RAIL_ITEMS - builtInItemCount.get() - getReservedPluginSlotCount())
            .coerceAtLeast(0)

    fun canAddPluginItems(count: Int): Boolean = count <= getAvailableSlotsForPlugins()

    fun getDeclaredSlots(pluginId: String): Int = reservedPluginSlots[pluginId] ?: 0

    @Throws(SidebarSlotExceededException::class)
    fun reservePluginSlots(pluginId: String, count: Int) {
        if (count <= 0) return

        val available = getAvailableSlotsForPlugins()
        if (count > available) {
            throw SidebarSlotExceededException(count, available, pluginId)
        }
        reservedPluginSlots[pluginId] = count
    }

    fun releasePluginSlots(pluginId: String) {
        reservedPluginSlots.remove(pluginId)
    }

    fun reset() {
        builtInItemCount.set(0)
        reservedPluginSlots.clear()
    }
}
