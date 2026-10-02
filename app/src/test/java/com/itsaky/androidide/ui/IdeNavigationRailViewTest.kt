package com.itsaky.androidide.ui

import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IdeNavigationRailViewTest {
	@Test
	fun `holds more items than a material navigation rail allows`() {
		val context =
			ContextThemeWrapper(
				ApplicationProvider.getApplicationContext(),
				com.google.android.material.R.style.Theme_Material3_DayNight,
			)
		val rail = IdeNavigationRailView(context)

		repeat(ITEM_COUNT) { rail.menu.add(0, it + 1, it, "Item $it") }

		assertThat(rail.menu.size()).isEqualTo(ITEM_COUNT)
	}

	private companion object {
		const val ITEM_COUNT = 21
	}
}
