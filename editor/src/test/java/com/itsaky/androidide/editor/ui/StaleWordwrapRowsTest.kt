package com.itsaky.androidide.editor.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.managers.PreferenceManager
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.layout.WordwrapLayout
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** ADFA-6543: a word-wrap row starting past the end of its (shrunk) line must not crash drawing. */
@RunWith(RobolectricTestRunner::class)
class StaleWordwrapRowsTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	@Before
	fun setUp() {
		val app = mockk<BaseApplication>(relaxed = true) { every { prefManager } returns PreferenceManager(context) }
		mockkObject(BaseApplication.Companion)
		every { BaseApplication.baseInstance } returns app
	}

	@After
	fun tearDown() {
		unmockkObject(BaseApplication.Companion)
	}

	@Test
	fun staleRowIsSkippedAndLayoutRebuilt() {
		val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
		// View.post only runs once the editor is attached, as it always is when it draws.
		val editor = IDEEditor(activity)
		activity.setContentView(editor)
		editor.setText("a".repeat(20))
		editor.isWordwrap = true
		editor.measure(
			View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
		)
		editor.layout(0, 0, 1080, 1920)
		ShadowLooper.idleMainLooper()

		val rowTable = rowTableOf(editor)
		val regionCtor =
			Class
				.forName("io.github.rosemoe.sora.widget.layout.WordwrapLayout\$RowRegion")
				.getDeclaredConstructor(Int::class.java, Int::class.java, Int::class.java)
				.apply { isAccessible = true }
		// What the async re-break leaves behind when the line was 120+ chars at snapshot time.
		rowTable.clear()
		rowTable.add(regionCtor.newInstance(0, 0, 110))
		val staleRow = regionCtor.newInstance(0, 110, 120)
		rowTable.add(staleRow)

		editor.draw(Canvas(Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)))
		ShadowLooper.idleMainLooper()

		assertThat(rowTableOf(editor)).doesNotContain(staleRow)
	}

	@Suppress("UNCHECKED_CAST")
	private fun rowTableOf(editor: CodeEditor): MutableList<Any> {
		val layout =
			CodeEditor::class.java
				.getDeclaredField("layout")
				.apply { isAccessible = true }
				.get(editor) as WordwrapLayout
		return WordwrapLayout::class.java
			.getDeclaredField("rowTable")
			.apply { isAccessible = true }
			.get(layout) as MutableList<Any>
	}
}
