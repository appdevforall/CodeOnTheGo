
package org.appdevforall.codeonthego.actions

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object SidebarSlotManager {
	private val builtInItemCount = AtomicInteger(0)
	private val reservedPluginSlots = ConcurrentHashMap<String, Int>()

	fun setBuiltInItemCount(count: Int) {
		require(count >= 0) { "Built-in item count must not be negative" }
		builtInItemCount.set(count)
	}

	fun getBuiltInItemCount(): Int = builtInItemCount.get()

	fun getReservedPluginSlotCount(): Int = reservedPluginSlots.values.sum()

	fun getTotalItemCount(): Int = builtInItemCount.get() + getReservedPluginSlotCount()

	fun getDeclaredSlots(pluginId: String): Int = reservedPluginSlots[pluginId] ?: 0

	fun reservePluginSlots(
		pluginId: String,
		count: Int,
	) {
		if (count <= 0) return
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
