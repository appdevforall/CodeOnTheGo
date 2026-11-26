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

package com.itsaky.androidide.activities

import android.content.Context
import android.text.StaticLayout
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.R
import com.itsaky.androidide.common.databinding.LayoutIconTitleDescriptionBinding
import com.itsaky.androidide.models.SimpleIconTitleDescriptionItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * About's Socials rows at 2x font scale (ADFA-7317). A title capped at one line with bottom gravity
 * showed the wrapped text's last line ("Support and discussions forum" read "discussions forum"), and
 * a description capped at three lines cut the forum link's fourth. Native graphics, so the text is
 * measured with real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(fontScale = 2.0f, qualifiers = "w360dp-h640dp-xhdpi")
class AboutSocialRowsLargeTextTest {
	private val themed: Context =
		ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_AndroidIDE)

	private val forum
		get() = row(R.string.discussions_on_telegram, R.string.github_discussions_url)

	private val telegram
		get() = row(R.string.official_tg_channel, R.string.telegram_channel_url)

	/** The row for [title] and [description], bound by About's adapter and laid out across the screen. */
	private fun row(
		title: Int,
		description: Int,
	): LayoutIconTitleDescriptionBinding {
		val item =
			SimpleIconTitleDescriptionItem(0, null, themed.getString(title), themed.getString(description))
		val adapter = AboutActivity.AboutSocialItemsAdapter(listOf(item)) {}
		val holder = adapter.onCreateViewHolder(FrameLayout(themed), 0)
		adapter.onBindViewHolder(holder, 0)

		// The whole width of a 360dp phone, wider than the row gets inside About's card, so text that
		// wraps here wraps on the device too.
		val width = themed.resources.displayMetrics.widthPixels
		val root = holder.binding.root
		root.measure(
			View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
		)
		root.layout(0, 0, width, root.measuredHeight)
		return holder.binding
	}

	/** [view]'s text laid out at the view's own width with no line cap: what it takes to show it all. */
	private fun uncapped(view: TextView): StaticLayout {
		val text = view.text
		return StaticLayout.Builder
			.obtain(text, 0, text.length, view.paint, view.width - view.compoundPaddingLeft - view.compoundPaddingRight)
			.setLineSpacing(view.lineSpacingExtra, view.lineSpacingMultiplier)
			.setIncludePad(view.includeFontPadding)
			.setBreakStrategy(view.breakStrategy)
			.setHyphenationFrequency(view.hyphenationFrequency)
			.build()
	}

	private fun assertShowsEveryLine(
		view: TextView,
		minLines: Int,
	) {
		val needed = uncapped(view)
		assertWithMessage("\"%s\" has to wrap to more than %s lines for this test to mean anything", view.text, minLines)
			.that(needed.lineCount)
			.isGreaterThan(minLines)
		assertWithMessage("\"%s\" needs %spx for its %s lines", view.text, needed.height, needed.lineCount)
			.that(view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
			.isAtLeast(needed.height)
	}

	@Test
	fun `a title that wraps at 2x font scale shows every line`() {
		assertShowsEveryLine(forum.title, minLines = 1)
		assertShowsEveryLine(telegram.title, minLines = 1)
	}

	@Test
	fun `the forum link shows every line at 2x font scale`() {
		// More than the three lines About used to allow, and the two the layout did.
		assertShowsEveryLine(forum.description, minLines = 3)
	}
}
