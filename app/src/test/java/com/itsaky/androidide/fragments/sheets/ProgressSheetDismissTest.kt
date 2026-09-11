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

package com.itsaky.androidide.fragments.sheets

import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `DialogFragment.show` only enqueues the add transaction, so a dismiss issued in the same
 * main-thread pass lands before the sheet exists. Callers that show a progress sheet around work
 * that can finish synchronously - `IDELanguageClientImpl.performCodeAction` - rely on that dismiss
 * being honoured; dropping it strands the sheet on screen with nothing left to close it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProgressSheetDismissTest.TestApp::class)
class ProgressSheetDismissTest {
	open class TestApp : BaseApplication()

	@Test
	fun `dismiss issued before the show transaction runs still closes the sheet`() {
		val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
		val manager = activity.supportFragmentManager

		val sheet = ProgressSheet()
		sheet.isCancelable = false
		sheet.show(manager, TAG)
		sheet.dismiss()

		shadowOf(Looper.getMainLooper()).idle()
		manager.executePendingTransactions()

		assertThat(manager.findFragmentByTag(TAG)).isNull()
		assertThat(sheet.isShowing).isFalse()
	}

	@Test
	fun `dismiss issued once the sheet is on screen closes it`() {
		val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
		val manager = activity.supportFragmentManager

		val sheet = ProgressSheet()
		sheet.isCancelable = false
		sheet.show(manager, TAG)

		shadowOf(Looper.getMainLooper()).idle()
		manager.executePendingTransactions()
		assertThat(sheet.isShowing).isTrue()

		sheet.dismiss()

		shadowOf(Looper.getMainLooper()).idle()
		manager.executePendingTransactions()

		assertThat(manager.findFragmentByTag(TAG)).isNull()
		assertThat(sheet.isShowing).isFalse()
	}

	private companion object {
		const val TAG = "progress_sheet_test"
	}
}
