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

package com.itsaky.androidide.adapters.onboarding

import android.Manifest
import android.content.Context
import android.text.StaticLayout
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.R
import com.itsaky.androidide.models.OnboardingPermissionItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A permission card's title at 2x font scale (ADFA-7290). A title capped at one line with bottom
 * gravity kept one line's height and showed the wrapped text's last line: "Install packages" read
 * "packages". Native graphics, so the text is measured with real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingPermissionItemLayoutTest {
	private val themed: Context =
		ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_AndroidIDE)

	/** The card for [title], bound by the real adapter and laid out at [width] px. */
	private fun laidOutTitle(
		title: Int,
		width: Int,
	): TextView {
		val adapter =
			OnboardingPermissionsAdapter(
				listOf(
					OnboardingPermissionItem(
						Manifest.permission.REQUEST_INSTALL_PACKAGES,
						title,
						R.string.permission_desc_install_packages,
						isGranted = false,
					),
				),
				requestPermission = {},
			)
		val holder = adapter.onCreateViewHolder(FrameLayout(themed), 0)
		adapter.onBindViewHolder(holder, 0)

		val card = holder.binding.root
		card.measure(
			View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
		)
		card.layout(0, 0, width, card.measuredHeight)
		return holder.binding.infoContent.title
	}

	/** The title's text laid out at the title's own width with no line cap: what it takes to show it all. */
	private fun uncapped(title: TextView): StaticLayout {
		val text = title.text
		return StaticLayout.Builder
			.obtain(text, 0, text.length, title.paint, title.width - title.compoundPaddingLeft - title.compoundPaddingRight)
			.setLineSpacing(title.lineSpacingExtra, title.lineSpacingMultiplier)
			.setIncludePad(title.includeFontPadding)
			.build()
	}

	@Test
	@Config(fontScale = 2.0f, qualifiers = "w360dp-h640dp-xhdpi")
	fun `a title that wraps at 2x font scale shows every line`() {
		// The whole width of a 360dp phone, wider than the card gets on the screen, so a title that
		// wraps here wraps on the device too.
		val width = themed.resources.displayMetrics.widthPixels
		for (res in listOf(R.string.permission_title_install_packages, R.string.permission_title_overlay_window)) {
			val title = laidOutTitle(res, width)
			val needed = uncapped(title)

			assertWithMessage("\"%s\" has to wrap at %spx for this test to mean anything", title.text, width)
				.that(needed.lineCount)
				.isGreaterThan(1)
			assertWithMessage("\"%s\" needs %spx for its %s lines", title.text, needed.height, needed.lineCount)
				.that(title.height - title.compoundPaddingTop - title.compoundPaddingBottom)
				.isAtLeast(needed.height)
		}
	}
}
