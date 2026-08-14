package com.itsaky.androidide.fragments

import android.content.Context
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.preferences.IPreference
import com.itsaky.androidide.preferences.IPreferenceGroup
import com.itsaky.androidide.preferences.IPreferenceScreen
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.junit.Assert.assertThrows
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

	@Test
	fun `collectTooltipTags rejects a duplicate key instead of silently overwriting its tag`() {
		val children =
			listOf(
				FakeItem(key = "dup", tooltipTag = "tag.first"),
				FakeItem(key = "dup", tooltipTag = "tag.second"),
			)

		assertThrows(IllegalStateException::class.java) {
			IDEPreferencesFragment().collectTooltipTags(children)
		}
	}

	@Test
	fun `resolveTooltipTag looks up the real adapter position's own tag`() {
		val recyclerView = buildRecyclerView(keys = listOf("a", "b"))
		val fragment =
			IDEPreferencesFragment().apply {
				tooltipTagsByKey = mapOf("a" to "tag.a", "b" to "tag.b")
			}

		val rowB = recyclerView.getChildAt(1)

		assertThat(fragment.resolveTooltipTag(recyclerView, rowB)).isEqualTo("tag.b")
	}

	@Test
	fun `resolveTooltipTag returns null for a row whose own tag is empty`() {
		val recyclerView = buildRecyclerView(keys = listOf("a"))
		val fragment =
			IDEPreferencesFragment().apply {
				tooltipTagsByKey = mapOf("a" to "")
			}

		val rowA = recyclerView.getChildAt(0)

		assertThat(fragment.resolveTooltipTag(recyclerView, rowA)).isNull()
	}

	/** A real, laid-out RecyclerView backed by a real PreferenceGroupAdapter - not a fake. */
	private fun buildRecyclerView(keys: List<String>): RecyclerView {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val screen = PreferenceManager(context).createPreferenceScreen(context)
		keys.forEach { screen.addPreference(Preference(context).apply { key = it }) }

		return RecyclerView(context).apply {
			layoutManager = LinearLayoutManager(context)
			adapter = PreferenceGroupAdapter(screen)
			measure(
				View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
			)
			layout(0, 0, 1000, 1000)
		}
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
