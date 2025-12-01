

package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.actions.SidebarSlotManager
import com.itsaky.androidide.plugins.services.IdeSidebarService

class IdeSidebarServiceImpl(
    private val pluginId: String
) : IdeSidebarService {

    override fun getAvailableSidebarSlots(): Int = SidebarSlotManager.getAvailableSlotsForPlugins()

    override fun canAddSidebarItems(count: Int): Boolean = SidebarSlotManager.canAddPluginItems(count)

    override fun getMaxSidebarItems(): Int = SidebarSlotManager.MAX_NAVIGATION_RAIL_ITEMS

    override fun getCurrentSidebarItemCount(): Int = SidebarSlotManager.getTotalItemCount()

    override fun getDeclaredSidebarSlots(): Int = SidebarSlotManager.getDeclaredSlots(pluginId)
}
