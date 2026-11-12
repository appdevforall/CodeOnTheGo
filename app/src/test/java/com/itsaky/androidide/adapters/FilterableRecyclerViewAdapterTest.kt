package com.itsaky.androidide.adapters

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
		override fun getQueryCandidate(item: String) = item

		override fun onCreateViewHolder(
			parent: ViewGroup,
			viewType: Int,
		) = object : RecyclerView.ViewHolder(View(parent.context)) {}

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

		override fun onChanged() {
			rows.clear()
			rows.addAll(List(adapter.itemCount) { adapter.getItem(it) })
		}

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
		val expected = tasks.filter { it.contains(query.trim(), ignoreCase = true) }
		filter(query)
		// Filtering runs on Dispatchers.Default, then posts its result to the main looper.
		val deadline = System.currentTimeMillis() + 2_000
		while (List(itemCount) { getItem(it) } != expected) {
			check(System.currentTimeMillis() < deadline) { "filter('$query') did not finish" }
			shadowOf(Looper.getMainLooper()).idle()
		}
	}

	@Test
	fun `refining a query keeps the shown rows in step with the filtered data`() {
		val adapter = StringAdapter(tasks)
		val shown = ShownRows(adapter)
		adapter.registerAdapterDataObserver(shown)

		for (query in listOf("c", "co", "cor", "core", "core:c", "", "comp")) {
			adapter.filterAndWait(query)
			val data = List(adapter.itemCount) { adapter.getItem(it) }
			assertThat(shown.rows.filterNotNull()).isEqualTo(data.filter { it in shown.rows })
			assertThat(shown.rows).hasSize(data.size)
		}
	}
}
