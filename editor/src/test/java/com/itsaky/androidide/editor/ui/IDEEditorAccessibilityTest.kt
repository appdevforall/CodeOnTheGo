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
 *  along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.editor.ui

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.editor.utils.EditorAccessibilitySegments
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class IDEEditorAccessibilityTest {
	private lateinit var context: Context
	private lateinit var editor: TestIDEEditor
	private lateinit var a11yManager: AccessibilityManager

	private class TestIDEEditor(
		context: Context,
	) : IDEEditor(context) {
		public override fun dispatchHoverEvent(event: MotionEvent): Boolean = super.dispatchHoverEvent(event)
	}

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		a11yManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
		editor = TestIDEEditor(context)
		editor.isWordwrap = false
		editor.setText("Line 1: first\nLine 2: second\nLine 3: third")
		shadowOf(android.os.Looper.getMainLooper()).idle()
	}

	@After
	fun tearDown() {
		editor.release()
	}

	@Test
	fun `node reports as EditText with click and movement actions`() {
		val info = editor.createAccessibilityNodeInfo()
		assertThat(info).isNotNull()
		assertThat(info!!.className).isEqualTo(EditText::class.java.name)
		assertThat(info.isClickable).isTrue()
		assertThat(info.movementGranularities).isEqualTo(EditorAccessibilitySegments.SUPPORTED_GRANULARITIES)

		val actions = info.actionList.map { it.id }
		assertThat(actions).contains(AccessibilityNodeInfo.ACTION_CLICK)
		assertThat(actions).contains(AccessibilityNodeInfo.ACTION_SET_SELECTION)
		assertThat(actions).contains(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
		assertThat(actions).contains(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
	}

	@Suppress("DEPRECATION")
	@Test
	fun `onInitializeAccessibilityNodeInfo populates click and movement actions`() {
		val info = AccessibilityNodeInfo.obtain()
		editor.onInitializeAccessibilityNodeInfo(info)

		assertThat(info.className).isEqualTo(EditText::class.java.name)
		assertThat(info.isClickable).isTrue()
		val actions = info.actionList.map { it.id }
		assertThat(actions).contains(AccessibilityNodeInfo.ACTION_CLICK)
		assertThat(actions).contains(AccessibilityNodeInfo.ACTION_SET_SELECTION)
	}

	@Test
	fun `hover exit does not overwrite coordinates for subsequent accessibility click`() {
		shadowOf(a11yManager).apply {
			setEnabled(true)
			setTouchExplorationEnabled(true)
		}

		val downTime = SystemClock.uptimeMillis()
		val enterEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_HOVER_ENTER, 50f, 10f, 0)
		val moveEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_HOVER_MOVE, 60f, 15f, 0)
		val exitEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_HOVER_EXIT, -1f, -1f, 0)

		assertThat(editor.dispatchHoverEvent(enterEvent)).isTrue()
		assertThat(editor.dispatchHoverEvent(moveEvent)).isTrue()
		assertThat(editor.dispatchHoverEvent(exitEvent)).isTrue()

		// An ensuing ACTION_CLICK should still succeed and place caret without crashing or failing
		val clickHandled = editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
		assertThat(clickHandled).isTrue()
		assertThat(editor.cursor).isNotNull()

		shadowOf(android.os.Looper.getMainLooper()).idle()

		// Verify click accessibility event was dispatched
		val events = shadowOf(a11yManager).sentAccessibilityEvents
		val clickedEvents = events.filter { it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED }
		assertThat(clickedEvents).isNotEmpty()
	}

	@Test
	fun `accessibility click succeeds when no prior hover occurred`() {
		shadowOf(a11yManager).apply {
			setEnabled(true)
			setTouchExplorationEnabled(true)
		}

		val clickHandled = editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
		assertThat(clickHandled).isTrue()
		assertThat(editor.cursor).isNotNull()
	}
}
