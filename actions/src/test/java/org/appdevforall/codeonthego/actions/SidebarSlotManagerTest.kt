package org.appdevforall.codeonthego.actions

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class SidebarSlotManagerTest {
	@After
	fun tearDown() = SidebarSlotManager.reset()

	@Test
	fun `a plugin can declare more sidebar items than fit beside the built-in ones`() {
		SidebarSlotManager.setBuiltInItemCount(7)

		SidebarSlotManager.reservePluginSlots("plugin.a", 14)

		assertThat(SidebarSlotManager.getDeclaredSlots("plugin.a")).isEqualTo(14)
		assertThat(SidebarSlotManager.getTotalItemCount()).isEqualTo(21)
	}
}
