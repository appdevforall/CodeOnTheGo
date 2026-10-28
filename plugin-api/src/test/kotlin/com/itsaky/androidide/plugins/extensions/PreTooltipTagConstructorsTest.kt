package com.itsaky.androidide.plugins.extensions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreTooltipTagConstructorsTest {
	private val string = String::class.java
	private val boxedInt = Int::class.javaObjectType
	private val int = Int::class.javaPrimitiveType!!
	private val boolean = Boolean::class.javaPrimitiveType!!
	private val function0 = Function0::class.java
	private val list = List::class.java
	private val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")

	private val action: () -> Unit = {}
	private val fragmentFactory: () -> Any = { error("not called") }
	private val tabItemPrimary = TabItem::class.java.getConstructor(string, string, function0, boolean, boolean, int, string)

	@Test
	fun navigationItemKeepsItsFullConstructor() {
		val item =
			NavigationItem::class.java
				.getConstructor(string, string, boxedInt, boolean, boolean, string, int, function0)
				.newInstance("id", "Title", 7, false, false, "group", 3, action)

		assertThat(item).isEqualTo(NavigationItem("id", "Title", 7, false, false, "group", 3, tooltipTag = null, action = action))
	}

	@Test
	fun navigationItemKeepsItsDefaultArgumentConstructor() {
		val item =
			NavigationItem::class.java
				.getConstructor(string, string, boxedInt, boolean, boolean, string, int, function0, int, marker)
				.newInstance("id", "Title", 7, false, false, "group", 3, action, 0b1111100, null)

		assertThat(item).isEqualTo(NavigationItem("id", "Title", action = action))
	}

	@Test
	fun tabItemKeepsItsFullConstructor() {
		val item =
			TabItem::class.java
				.getConstructor(string, string, function0, boolean, boolean, int)
				.newInstance("id", "Title", fragmentFactory, false, false, 3)

		assertThat(item).isEqualTo(tabItemPrimary.newInstance("id", "Title", fragmentFactory, false, false, 3, null))
	}

	@Test
	fun tabItemKeepsItsDefaultArgumentConstructor() {
		val item =
			TabItem::class.java
				.getConstructor(string, string, function0, boolean, boolean, int, int, marker)
				.newInstance("id", "Title", fragmentFactory, false, false, 3, 0b111000, null)

		assertThat(item).isEqualTo(tabItemPrimary.newInstance("id", "Title", fragmentFactory, true, true, 0, null))
	}

	@Test
	fun fileTabMenuItemKeepsItsFullConstructor() {
		val item =
			FileTabMenuItem::class.java
				.getConstructor(string, string, boxedInt, boolean, boolean, int, function0)
				.newInstance("id", "Title", 7, false, false, 3, action)

		assertThat(item).isEqualTo(FileTabMenuItem("id", "Title", 7, false, false, 3, tooltipTag = null, action = action))
	}

	@Test
	fun fileTabMenuItemKeepsItsDefaultArgumentConstructor() {
		val item =
			FileTabMenuItem::class.java
				.getConstructor(string, string, boxedInt, boolean, boolean, int, function0, int, marker)
				.newInstance("id", "Title", 7, false, false, 3, action, 0b111100, null)

		assertThat(item).isEqualTo(FileTabMenuItem("id", "Title", action = action))
	}

	@Test
	fun menuItemKeepsItsDefaultArgumentConstructor() {
		val item =
			MenuItem::class.java
				.getConstructor(string, string, boolean, boolean, string, list, function0, int, marker)
				.newInstance("id", "Title", false, false, "Ctrl+S", null, action, 0b111100, null)

		assertThat(item).isEqualTo(MenuItem("id", "Title", action = action))
	}

	@Test
	fun menuItemDefaultsOnlyTheArgumentsTheMaskNames() {
		val subItems = listOf(MenuItem("child", "Child", action = action))

		val item =
			MenuItem::class.java
				.getConstructor(string, string, boolean, boolean, string, list, function0, int, marker)
				.newInstance("id", "Title", false, false, "Ctrl+S", subItems, action, 0b010000, null)

		assertThat(item).isEqualTo(MenuItem("id", "Title", false, false, null, subItems, action))
	}

	@Test
	fun kotlinCallersStillResolveToThePrimaryConstructors() {
		assertThat(NavigationItem("id", "Title", action = action).tooltipTag).isNull()
		assertThat(FileTabMenuItem("id", "Title", action = action).tooltipTag).isNull()
		assertThat(MenuItem("id", "Title", action = action).tooltipTag).isNull()
	}
}
