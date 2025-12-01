

package com.itsaky.androidide.plugins.services

interface IdeSidebarService {

    fun getAvailableSidebarSlots(): Int

    fun canAddSidebarItems(count: Int): Boolean

    fun getMaxSidebarItems(): Int

    fun getCurrentSidebarItemCount(): Int

    fun getDeclaredSidebarSlots(): Int
}
