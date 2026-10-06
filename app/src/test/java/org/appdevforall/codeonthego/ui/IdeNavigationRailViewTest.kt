package org.appdevforall.codeonthego.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
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

	@Test
	fun `scrolls to the last item when the rail has a header`() = assertScrollsToLastItem(withHeader = true)

	@Test
	fun `scrolls to the last item when the rail has no header`() = assertScrollsToLastItem(withHeader = false)

	private fun assertScrollsToLastItem(withHeader: Boolean) {
		val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
		val rail =
			IdeNavigationRailView(
				ContextThemeWrapper(activity, com.google.android.material.R.style.Theme_Material3_DayNight),
			)
		if (withHeader) {
			rail.addHeaderView(
				FrameLayout(rail.context).apply {
					addView(View(context), FrameLayout.LayoutParams(1, HEADER_HEIGHT))
				},
			)
		}
		repeat(ITEM_COUNT) { rail.menu.add(0, it + 1, it, "Item $it") }
		activity.setContentView(rail, ViewGroup.LayoutParams(RAIL_WIDTH, RAIL_HEIGHT))
		shadowOf(Looper.getMainLooper()).idle()
		rail.measure(
			View.MeasureSpec.makeMeasureSpec(RAIL_WIDTH, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(RAIL_HEIGHT, View.MeasureSpec.EXACTLY),
		)
		rail.layout(0, 0, RAIL_WIDTH, RAIL_HEIGHT)

		val scroll = rail.children.filterIsInstance<NestedScrollView>().single()
		val menu = scroll.getChildAt(0) as ViewGroup
		assertThat(scroll.top).isAtLeast(rail.headerView?.bottom ?: 0)
		assertThat(scroll.canScrollVertically(1)).isTrue()

		scroll.scrollTo(0, menu.height)

		val lastItem = menu.getChildAt(menu.childCount - 1)
		assertThat(menu.top + lastItem.bottom - scroll.scrollY).isAtMost(scroll.height)
	}

	private companion object {
		const val ITEM_COUNT = 21
		const val HEADER_HEIGHT = 120
		const val RAIL_WIDTH = 80
		const val RAIL_HEIGHT = 600
	}
}
