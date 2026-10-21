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
import android.view.inputmethod.InputMethodManager
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

		// Changing caret location manually and performing second click without hover
		editor.setSelection(2, 0)
		assertThat(editor.cursor?.leftLine).isEqualTo(2)

		val secondClick = editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
		assertThat(secondClick).isTrue()
		// Must not jump back to the previous hover position (line 0)
		assertThat(editor.cursor?.leftLine).isEqualTo(2)
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

	@Test
	fun `accessibility click requests soft keyboard when editable`() {
		val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

		val clickHandled = editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
		assertThat(clickHandled).isTrue()

		shadowOf(android.os.Looper.getMainLooper()).idle()
		assertThat(shadowOf(imm).isSoftInputVisible).isTrue()
	}

	@Test
	fun `showSoftInput respects checkSoftInputEnabled when soft keyboard is disabled`() {
		val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
		editor.isSoftKeyboardEnabled = false

		editor.showSoftInput()
		shadowOf(android.os.Looper.getMainLooper()).idle()

		assertThat(shadowOf(imm).isSoftInputVisible).isFalse()
	}

	@Test
	fun `text change event is emitted with bounded payload when touch exploration enabled`() {
		shadowOf(a11yManager).apply {
			setEnabled(true)
			setTouchExplorationEnabled(true)
		}

		editor.text.insert(0, 0, "PREFIX_")
		shadowOf(android.os.Looper.getMainLooper()).idle()

		val events = shadowOf(a11yManager).sentAccessibilityEvents
		val textEvents = events.filter { it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED }
		assertThat(textEvents).isNotEmpty()
		val event = textEvents.last()
		assertThat(event.addedCount).isEqualTo("PREFIX_".length)
		assertThat(event.removedCount).isEqualTo(0)
		assertThat(event.fromIndex).isEqualTo(0)
	}

	@Test
	fun `selection change event is emitted with bounded payload`() {
		shadowOf(a11yManager).apply {
			setEnabled(true)
			setTouchExplorationEnabled(true)
		}

		editor.setSelection(0, 4)
		shadowOf(android.os.Looper.getMainLooper()).idle()

		val events = shadowOf(a11yManager).sentAccessibilityEvents
		val selEvents = events.filter { it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED }
		assertThat(selEvents).isNotEmpty()
		val event = selEvents.last()
		assertThat(event.fromIndex).isEqualTo(4)
		assertThat(event.toIndex).isEqualTo(4)
	}

	@Test
	fun `setText captures previous document text for ACTION_SET_NEW_TEXT`() {
		shadowOf(a11yManager).apply {
			setEnabled(true)
			setTouchExplorationEnabled(true)
		}

		editor.setText("First content")
		shadowOf(android.os.Looper.getMainLooper()).idle()

		editor.setText("Second content")
		shadowOf(android.os.Looper.getMainLooper()).idle()

		val events = shadowOf(a11yManager).sentAccessibilityEvents
		val textEvents = events.filter { it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED }
		assertThat(textEvents).isNotEmpty()
		val event = textEvents.last()
		assertThat(event.removedCount).isEqualTo("First content".length)
		assertThat(event.beforeText.toString()).isEqualTo("First content")
	}

	@Test
	fun `selection change event uses absolute indices past the first window`() {
		enableTouchExploration()
		val line = "x".repeat(99) + "\n"
		val content = line.repeat(5)
		editor.setText(content)
		shadowOf(android.os.Looper.getMainLooper()).idle()

		editor.setSelection(3, 10)
		shadowOf(android.os.Looper.getMainLooper()).idle()

		val event = lastEvent(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
		assertThat(event.fromIndex).isEqualTo(3 * line.length + 10)
		assertThat(event.toIndex).isEqualTo(3 * line.length + 10)
		assertThat(event.itemCount).isEqualTo(content.length)
		assertThat(event.text.single().toString()).isEqualTo(content)
	}

	@Test
	fun `large insert announces a bounded slice with consistent counts`() {
		enableTouchExploration()
		val pasted = "y".repeat(5_000)

		editor.text.insert(0, 0, pasted)
		shadowOf(android.os.Looper.getMainLooper()).idle()

		val event = lastEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
		val text = event.text.single().toString()
		assertThat(event.addedCount).isLessThan(pasted.length)
		assertThat(text.length).isLessThan(pasted.length)
		assertThat(text.removeRange(event.fromIndex, event.fromIndex + event.addedCount))
			.isEqualTo(event.beforeText.toString())
	}

	@Test
	fun `large delete announces a bounded slice with consistent counts`() {
		enableTouchExploration()
		editor.setText("z".repeat(5_000) + "\nend")
		shadowOf(android.os.Looper.getMainLooper()).idle()

		editor.text.delete(0, 0, 0, 5_000)
		shadowOf(android.os.Looper.getMainLooper()).idle()

		val event = lastEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
		val before = event.beforeText.toString()
		assertThat(event.removedCount).isLessThan(5_000)
		assertThat(before.removeRange(event.fromIndex, event.fromIndex + event.removedCount))
			.isEqualTo(event.text.single().toString())
	}

	@Test
	fun `new text event addedCount matches the text it carries`() {
		enableTouchExploration()

		editor.setText("w".repeat(1_000))
		shadowOf(android.os.Looper.getMainLooper()).idle()

		val event = lastEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
		assertThat(event.addedCount).isEqualTo(event.text.single().length)
	}

	@Test
	fun `performClick does not show the keyboard without touch exploration`() {
		val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

		editor.performClick()
		shadowOf(android.os.Looper.getMainLooper()).idle()

		assertThat(shadowOf(imm).isSoftInputVisible).isFalse()
	}

	@Test
	fun `accessibility click runs the click listener`() {
		var clicked = false
		editor.setOnClickListener { clicked = true }

		assertThat(editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)).isTrue()
		assertThat(clicked).isTrue()
	}

	@Test
	fun `accessibility click after a granularity move keeps the moved caret`() {
		enableTouchExploration()
		editor.setSelection(0, 0)
		val downTime = SystemClock.uptimeMillis()
		val hover = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_HOVER_MOVE, 5f, 1f, 0)
		editor.dispatchHoverEvent(hover)

		assertThat(moveByWord(forward = true, extend = false)).isTrue()
		val moved = editor.cursor!!.left
		assertThat(moved).isGreaterThan(0)

		assertThat(editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)).isTrue()
		assertThat(editor.cursor!!.left).isEqualTo(moved)
	}

	@Test
	fun `reversing an extended selection shrinks it from the caret end`() {
		editor.setText("alpha beta gamma")
		shadowOf(android.os.Looper.getMainLooper()).idle()
		editor.setSelection(0, 10)

		assertThat(moveByWord(forward = false, extend = true)).isTrue() // [6, 10]
		assertThat(moveByWord(forward = false, extend = true)).isTrue() // [0, 10]
		assertThat(moveByWord(forward = true, extend = true)).isTrue() // [5, 10]

		assertThat(editor.cursor!!.left).isEqualTo(5)
		assertThat(editor.cursor!!.right).isEqualTo(10)
	}

	private fun moveByWord(
		forward: Boolean,
		extend: Boolean,
	): Boolean {
		val args =
			android.os.Bundle().apply {
				putInt(
					AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
					AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD,
				)
				putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, extend)
			}
		val action =
			if (forward) {
				AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
			} else {
				AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
			}
		return editor.performAccessibilityAction(action, args)
	}

	private fun enableTouchExploration() {
		shadowOf(a11yManager).apply {
			setEnabled(true)
			setTouchExplorationEnabled(true)
		}
	}

	private fun lastEvent(type: Int): AccessibilityEvent = shadowOf(a11yManager).sentAccessibilityEvents.last { it.eventType == type }
}
