package com.itsaky.androidide.activities.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That a toolbar icon is drawn with the theme tint and the disabled dim.
 *
 * ADFA-6312: a layer-list icon lost its tint because it was mutated after tinting, which rebuilt
 * its layers, so it drew its raw white paint on the light toolbar.
 */
@RunWith(RobolectricTestRunner::class)
class ToolbarIconTest {
	private val filter = PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_ATOP)

	@Test
	fun `every layer of a layered icon keeps the colour filter`() {
		val icon = LayerDrawable(arrayOf(FilterLayer(), FilterLayer()))

		val drawn = toolbarIcon(icon, filter, enabled = true) as LayerDrawable

		for (i in 0 until drawn.numberOfLayers) {
			assertThat(drawn.getDrawable(i).colorFilter).isSameInstanceAs(filter)
		}
	}

	/**
	 * A layer that, like framework drawables, keeps its filter per instance and is rebuilt from
	 * shared state; Robolectric's legacy Paint never reports a filter back, so ColorDrawable can't.
	 */
	private class FilterLayer : Drawable() {
		private var filter: ColorFilter? = null

		override fun draw(canvas: Canvas) {}

		override fun setAlpha(alpha: Int) {}

		override fun setColorFilter(colorFilter: ColorFilter?) {
			filter = colorFilter
		}

		override fun getColorFilter(): ColorFilter? = filter

		@Deprecated("Deprecated in Java")
		override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

		override fun getConstantState(): ConstantState = State

		private object State : ConstantState() {
			override fun newDrawable(): Drawable = FilterLayer()

			override fun getChangingConfigurations(): Int = 0
		}
	}

	@Test
	fun `a disabled icon is dimmed and an enabled one is not`() {
		// GradientDrawable reports alpha as set; ColorDrawable rounds 76 down to 75.
		assertThat(toolbarIcon(GradientDrawable(), filter, enabled = false)!!.alpha).isEqualTo(76)
		assertThat(toolbarIcon(GradientDrawable(), filter, enabled = true)!!.alpha).isEqualTo(255)
	}

	@Test
	fun `an action without an icon gets none`() {
		assertThat(toolbarIcon(null, filter, enabled = true)).isNull()
	}
}
