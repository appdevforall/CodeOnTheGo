package com.itsaky.androidide.fragments

import android.content.Context
import androidx.preference.Preference
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.preferences.IPreference
import com.itsaky.androidide.preferences.IPreferenceGroup
import com.itsaky.androidide.preferences.IPreferenceScreen
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IDEPreferencesFragmentTest {
	@Test
	fun `collectTooltipTags maps every leaf key to its tooltipTag`() {
		val children =
			listOf(
				FakeItem(key = "a", tooltipTag = "tag.a"),
				FakeItem(key = "b", tooltipTag = "tag.b"),
			)

		val tags = IDEPreferencesFragment().collectTooltipTags(children)

		assertThat(tags).containsExactly("a", "tag.a", "b", "tag.b")
	}

	@Test
	fun `collectTooltipTags recurses into nested categories but not into nested screens`() {
		val children =
			listOf(
				FakeCategory(
					key = "category",
					tooltipTag = "tag.category",
					children = listOf(FakeItem(key = "nested", tooltipTag = "tag.nested")),
				),
				FakeScreen(
					key = "screen",
					tooltipTag = "tag.screen",
					// A screen's own children belong to a different fragment instance and must not
					// be pulled into this screen's map.
					children = listOf(FakeItem(key = "hidden", tooltipTag = "tag.hidden")),
				),
			)

		val tags = IDEPreferencesFragment().collectTooltipTags(children)

		assertThat(tags).containsExactly(
			"category",
			"tag.category",
			"nested",
			"tag.nested",
			"screen",
			"tag.screen",
		)
	}

	@Test
	fun `collectTooltipTags preserves an empty tooltipTag rather than omitting the key`() {
		val children = listOf(FakeItem(key = "untagged", tooltipTag = ""))

		val tags = IDEPreferencesFragment().collectTooltipTags(children)

		assertThat(tags).containsExactly("untagged", "")
	}
}

@Parcelize
private class FakeItem(
	override val key: String,
	override val tooltipTag: String = "",
) : IPreference() {
	@IgnoredOnParcel
	override val title: Int = 0

	override fun onCreateView(context: Context): Preference = throw UnsupportedOperationException()
}

@Parcelize
private class FakeCategory(
	override val key: String,
	override val tooltipTag: String = "",
	override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {
	@IgnoredOnParcel
	override val title: Int = 0
}

@Parcelize
private class FakeScreen(
	override val key: String,
	override val tooltipTag: String = "",
	override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {
	@IgnoredOnParcel
	override val title: Int = 0
}
