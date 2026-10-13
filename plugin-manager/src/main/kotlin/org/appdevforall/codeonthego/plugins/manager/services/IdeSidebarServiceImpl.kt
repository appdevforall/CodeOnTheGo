

package org.appdevforall.codeonthego.plugins.manager.services

import org.appdevforall.codeonthego.actions.SidebarSlotManager
import org.appdevforall.codeonthego.plugins.services.IdeSidebarService

class IdeSidebarServiceImpl(
	private val pluginId: String,
) : IdeSidebarService {
	override fun getAvailableSidebarSlots(): Int = Int.MAX_VALUE

	override fun canAddSidebarItems(count: Int): Boolean = true

	override fun getMaxSidebarItems(): Int = Int.MAX_VALUE

	override fun getCurrentSidebarItemCount(): Int = SidebarSlotManager.getTotalItemCount()

	override fun getDeclaredSidebarSlots(): Int = SidebarSlotManager.getDeclaredSlots(pluginId)
}
