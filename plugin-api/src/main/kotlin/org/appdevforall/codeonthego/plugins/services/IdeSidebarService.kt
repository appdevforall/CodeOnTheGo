

package org.appdevforall.codeonthego.plugins.services

interface IdeSidebarService {

    fun getAvailableSidebarSlots(): Int

    fun canAddSidebarItems(count: Int): Boolean

    fun getMaxSidebarItems(): Int

    fun getCurrentSidebarItemCount(): Int

    fun getDeclaredSidebarSlots(): Int
}
