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
package com.itsaky.androidide.actions

import android.graphics.drawable.Drawable
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the two halves of code-action tooltip resolution that failed in ADFA-4510: finding a
 * submenu child by its menu item id, and reading a tag from whichever member the action overrode.
 *
 * Code actions are children of CodeActionsMenu and are never registered with the registry, so the
 * render path can only reach them through [ActionMenu.findAction]. They override the `tooltipTag`
 * property while the render path reads `retrieveTooltipTag()`, so both must resolve to the same
 * value.
 */
@RunWith(RobolectricTestRunner::class)
class ActionTooltipResolutionTest {
	private open class FakeAction(
		override val id: String,
	) : ActionItem {
		override var label: String = id
		override var visible: Boolean = true
		override var enabled: Boolean = true
		override var icon: Drawable? = null
		override var requiresUIThread: Boolean = false
		override var location: ActionItem.Location = ActionItem.Location.EDITOR_CODE_ACTIONS

		override suspend fun execAction(data: ActionData): Any = true
	}

	private class PropertyOnlyAction : FakeAction("fake.propertyOnly") {
		override var tooltipTag: String = "editor.codeactions.comment"
	}

	private class FunctionOnlyAction : FakeAction("fake.functionOnly") {
		override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = "editor.codeactions.gotodef"
	}

	private class UntaggedAction : FakeAction("fake.untagged")

	private class FakeMenu : ActionMenu {
		override val children: MutableSet<ActionItem> = mutableSetOf()
		override val id: String = "fake.menu"
		override var label: String = "Fake menu"
		override var visible: Boolean = true
		override var enabled: Boolean = true
		override var icon: Drawable? = null
		override var requiresUIThread: Boolean = false
		override var location: ActionItem.Location = ActionItem.Location.EDITOR_TEXT_ACTIONS
	}

	private fun menuOf(vararg actions: ActionItem) = FakeMenu().apply { actions.forEach(::addAction) }

	@Test
	fun `findAction by itemId returns the matching child`() {
		val child = PropertyOnlyAction()
		val menu = menuOf(UntaggedAction(), child)

		assertThat(menu.findAction(child.itemId)).isSameInstanceAs(child)
	}

	@Test
	fun `findAction by itemId returns null when no child matches`() {
		val menu = menuOf(UntaggedAction())

		assertThat(menu.findAction("nothing.registered".hashCode())).isNull()
	}

	@Test
	fun `retrieveTooltipTag reads an action that overrides only the property`() {
		assertThat(PropertyOnlyAction().retrieveTooltipTag(false))
			.isEqualTo("editor.codeactions.comment")
	}

	@Test
	fun `retrieveTooltipTag reads an action that overrides only the function`() {
		assertThat(FunctionOnlyAction().retrieveTooltipTag(false))
			.isEqualTo("editor.codeactions.gotodef")
	}

	@Test
	fun `retrieveTooltipTag is empty when the action overrides neither member`() {
		assertThat(UntaggedAction().retrieveTooltipTag(false)).isEmpty()
	}
}
