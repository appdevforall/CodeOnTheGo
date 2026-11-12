package com.itsaky.androidide.adapters

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FilterableRecyclerViewAdapterTest {
	private class StringAdapter(
		items: List<String>,
	) : FilterableRecyclerViewAdapter<RecyclerView.ViewHolder, String>(items) {
		var commits = 0

		override fun onCurrentListChanged(
			previousList: List<String>,
			currentList: List<String>,
		) {
			commits++
		}

		override fun getQueryCandidate(item: String) = item

		override fun onCreateViewHolder(
			parent: ViewGroup,
			viewType: Int,
		) = object : RecyclerView.ViewHolder(
			View(parent.context).apply { layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, ROW_HEIGHT) },
		) {}

		override fun onBindViewHolder(
			holder: RecyclerView.ViewHolder,
			position: Int,
		) = Unit
	}

	/** Replays change notifications onto a list, as a RecyclerView does with its rows. */
	private class ShownRows(
		private val adapter: StringAdapter,
	) : RecyclerView.AdapterDataObserver() {
		// Inserted rows are null: their content comes from a later bind, not the notification.
		val rows = MutableList<String?>(adapter.itemCount) { adapter.getItem(it) }

		// A full reset would resync the mirror and hide drift left by earlier queries.
		override fun onChanged(): Unit = error("filtering must dispatch a diff, not a full reset")

		override fun onItemRangeInserted(
			positionStart: Int,
			itemCount: Int,
		) {
			rows.addAll(positionStart, List(itemCount) { null })
		}

		override fun onItemRangeRemoved(
			positionStart: Int,
			itemCount: Int,
		) {
			repeat(itemCount) { rows.removeAt(positionStart) }
		}

		override fun onItemRangeMoved(
			fromPosition: Int,
			toPosition: Int,
			itemCount: Int,
		) {
			rows.add(toPosition, rows.removeAt(fromPosition))
		}
	}

	private val tasks =
		listOf(
			":app:assembleDebug",
			":app:check",
			":app:clean",
			":core:assemble",
			":core:check",
			":core:clean",
			":core:compileJava",
			":lsp:compileJava",
		)

	private fun StringAdapter.filterAndWait(query: String) {
		val before = commits
		filter(query)
		// The diff runs on a background executor, then commits on the main looper.
		val deadline = System.currentTimeMillis() + 2_000
		while (commits == before) {
			check(System.currentTimeMillis() < deadline) { "filter('$query') did not commit" }
			shadowOf(Looper.getMainLooper()).idle()
		}
	}

	@Test
	fun `refining a query keeps the shown rows in step with the filtered data`() {
		val adapter = StringAdapter(tasks)
		val shown = ShownRows(adapter)
		adapter.registerAdapterDataObserver(shown)

		// Narrowing, a query matching the same rows as the last, widening from a filtered list,
		// clearing, then filtering from the full list again.
		for (query in listOf("c", "co", "cor", "core", "core:c", "comp", "", "a", "app")) {
			adapter.filterAndWait(query)
			val expected = tasks.filter { it.contains(query, ignoreCase = true) }
			assertThat(List(adapter.itemCount) { adapter.getItem(it) }).isEqualTo(expected)
			// A surviving row in the wrong place, or a stale one, differs from the data at its index.
			assertThat(shown.rows.mapIndexed { i, row -> row ?: expected.getOrNull(i) }).isEqualTo(expected)
		}
	}

	@Test
	fun `a new result is shown from its first match, not from where the list was scrolled`() {
		val adapter = StringAdapter(tasks)
		val list =
			RecyclerView(ApplicationProvider.getApplicationContext()).apply {
				layoutManager = LinearLayoutManager(context)
				this.adapter = adapter
			}
		val layoutManager = list.layoutManager as LinearLayoutManager

		fun layOut() {
			list.measure(exactly(1_000), exactly(2 * ROW_HEIGHT))
			list.layout(0, 0, 1_000, 2 * ROW_HEIGHT)
		}

		adapter.filterAndWait("co")
		layOut()
		list.scrollToPosition(4)
		layOut()
		assertThat(layoutManager.findFirstVisibleItemPosition()).isGreaterThan(0)

		// Clearing the search inserts rows above the visible ones; the list must not stay anchored there.
		adapter.filterAndWait("")
		layOut()
		assertThat(layoutManager.findFirstVisibleItemPosition()).isEqualTo(0)
	}

	private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

	private companion object {
		const val ROW_HEIGHT = 100
	}
}
