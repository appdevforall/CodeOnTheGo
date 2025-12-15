

package com.itsaky.androidide.actions

class SidebarSlotExceededException(
    requestedSlots: Int,
    availableSlots: Int,
    pluginId: String? = null
) : RuntimeException(
    buildMessage(requestedSlots, availableSlots, pluginId)
) {
    companion object {
        private fun buildMessage(requested: Int, available: Int, pluginId: String?): String {
            val pluginInfo = pluginId?.let { " Plugin '$it'" } ?: ""
            return "Sidebar slot limit exceeded.$pluginInfo declared $requested sidebar item(s), " +
                "but only $available slot(s) available. " +
                "IdeNavigationRailView supports a maximum of ${SidebarSlotManager.MAX_NAVIGATION_RAIL_ITEMS} items."
        }
    }
}
