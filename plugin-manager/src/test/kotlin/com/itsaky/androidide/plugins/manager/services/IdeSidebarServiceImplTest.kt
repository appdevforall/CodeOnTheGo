package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.actions.SidebarSlotManager
import org.junit.After
import org.junit.Test

class IdeSidebarServiceImplTest {
	@After
	fun tearDown() = SidebarSlotManager.reset()

	@Test
	fun `a plugin can add sidebar items however many are already in the sidebar`() {
		SidebarSlotManager.setBuiltInItemCount(7)
		SidebarSlotManager.reservePluginSlots("plugin.a", 5)

		val service = IdeSidebarServiceImpl("plugin.b")

		assertThat(service.canAddSidebarItems(20)).isTrue()
		assertThat(service.getAvailableSidebarSlots()).isEqualTo(Int.MAX_VALUE)
		assertThat(service.getMaxSidebarItems()).isEqualTo(Int.MAX_VALUE)
	}
}
