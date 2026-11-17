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

package com.itsaky.androidide.adapters

import android.annotation.SuppressLint
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Filterable [RecyclerView.Adapter].
 *
 * [ListAdapter] diffs each result against the list on screen and drops results superseded by a
 * later query.
 *
 * @author Akash Yadav
 */
abstract class FilterableRecyclerViewAdapter<V : RecyclerView.ViewHolder, D : Any>(
	val items: List<D>,
) : ListAdapter<D, V>(
		object : DiffUtil.ItemCallback<D>() {
			override fun areItemsTheSame(
				oldItem: D,
				newItem: D,
			): Boolean = oldItem == newItem

			@SuppressLint("DiffUtilEquals")
			override fun areContentsTheSame(
				oldItem: D,
				newItem: D,
			): Boolean = oldItem == newItem
		},
	) {
	private var recyclerView: RecyclerView? = null

	init {
		submitList(items)
	}

	/**
	 * Filter the list with the given query.
	 *
	 * @param query The query.
	 */
	fun filter(query: String?) {
		val query = query?.trim()
		// A new result starts at its first match. ListAdapter would otherwise keep the scroll anchor,
		// leaving rows inserted above it (widening the query, clearing it) out of view.
		submitList(if (query.isNullOrEmpty()) items else items.filter { onFilter(it, query) }) {
			recyclerView?.scrollToPosition(0)
		}
	}

	override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
		super.onAttachedToRecyclerView(recyclerView)
		this.recyclerView = recyclerView
	}

	override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
		super.onDetachedFromRecyclerView(recyclerView)
		this.recyclerView = null
	}

	/** Get the list item at given index. */
	public override fun getItem(position: Int): D = super.getItem(position)

	/** Get the query candidate for the given list item. */
	abstract fun getQueryCandidate(item: D): String

	/** Called on every item when filtering the data. */
	protected open fun onFilter(
		item: D,
		query: String,
	): Boolean = getQueryCandidate(item).contains(query, ignoreCase = true)
}
