/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.utils

import android.view.LayoutInflater
import android.view.View
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.databinding.FileActionPopupWindowBinding
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The contract of `file_action_popup_window.xml`, which has two independent consumers:
 * `ActionMenuUtils.showPopupWindow` and `EditorHandlerActivity.showPluginTabPopup`.
 *
 * Wrapping the root in a ScrollView (so the menu can scroll at large font scales) silently broke the
 * second one: a ScrollView hosts exactly one child, `@id/action_items` already is that child, and
 * the plugin popup was still calling `binding.root.addView(...)`. It compiles, so only a runtime tap
 * on a docked plugin tab revealed it -- and that path needs an installed plugin that opens an editor
 * tab, which makes it awkward to reach from a test or by hand.
 *
 * So the invariant is pinned here instead of relying on someone exercising both popups: items go
 * into [FileActionPopupWindowBinding.actionItems], and the root will throw if anyone adds to it.
 */
@RunWith(RobolectricTestRunner::class)
class FileActionPopupLayoutTest {
	private fun inflate(): FileActionPopupWindowBinding =
		FileActionPopupWindowBinding.inflate(
			LayoutInflater.from(ApplicationProvider.getApplicationContext()),
			null,
			false,
		)

	@Test
	fun `the root scrolls and the items go in its single child`() {
		val binding = inflate()

		assertThat(binding.root).isInstanceOf(ScrollView::class.java)
		// The container is the ScrollView's one and only child, which is what makes the root
		// unavailable as a parent for menu items.
		assertThat(binding.root.childCount).isEqualTo(1)
		assertThat(binding.root.getChildAt(0)).isSameInstanceAs(binding.actionItems)
	}

	@Test
	fun `items added to the container are accepted`() {
		val binding = inflate()
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()

		binding.actionItems.addView(View(context))
		binding.actionItems.addView(View(context))

		assertThat(binding.actionItems.childCount).isEqualTo(2)
	}

	@Test
	fun `adding to the root throws, which is the crash both consumers must avoid`() {
		val binding = inflate()
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()

		// IllegalStateException("ScrollView can host only one direct child"). Asserting it here means
		// a future consumer that reaches for binding.root fails a test rather than crashing on a tap.
		assertThrows(IllegalStateException::class.java) {
			binding.root.addView(View(context))
		}
	}
}
