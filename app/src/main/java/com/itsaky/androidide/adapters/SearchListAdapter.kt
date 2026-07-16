/**
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with AndroidIDE. If not,
 * see <https:></https:>//www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.adapters

import android.graphics.PorterDuff.Mode.SRC_ATOP
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.blankj.utilcode.util.ThreadUtils
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.LayoutSearchResultGroupBinding
import com.itsaky.androidide.databinding.LayoutSearchResultItemBinding
import com.itsaky.androidide.databinding.LayoutSearchResultSectionBinding
import com.itsaky.androidide.models.FileExtension
import com.itsaky.androidide.models.SearchResult
import com.itsaky.androidide.syntax.colorschemes.SchemeAndroidIDE
import com.itsaky.androidide.syntax.highlighters.JavaHighlighter
import com.itsaky.androidide.utils.resolveAttr
import com.itsaky.androidide.viewmodel.EditorViewModel.SearchResultSection
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CompletableFuture

class SearchListAdapter(
	results: Map<File, List<SearchResult>?>,
	private val onFileClick: (File) -> Unit,
	private val onMatchClick: (SearchResult) -> Unit,
	keys: List<File>,
) : Adapter<ViewHolder>() {
	// Sections, files and matches are flattened into a single row list so the hosting
	// RecyclerView can recycle match rows; a nested RecyclerView would inflate every
	// match of a file at once.
	private var rows: List<Row> =
		buildRows(listOf(SearchResultSection(null, results.filterValues { it != null }.mapValues { it.value.orEmpty() })), keys)

	constructor(
		results: Map<File, List<SearchResult>?>,
		onFileClick: (File) -> Unit,
		onMatchClick: (SearchResult) -> Unit,
	) : this(results, onFileClick, onMatchClick, results.keys.toList())

	constructor(
		sections: List<SearchResultSection>,
		onFileClick: (File) -> Unit,
		onMatchClick: (SearchResult) -> Unit,
	) : this(emptyMap(), onFileClick, onMatchClick, emptyList()) {
		rows = buildRows(sections, null)
	}

	override fun getItemViewType(position: Int): Int =
		when (rows[position]) {
			is Row.Header -> VIEW_TYPE_HEADER
			is Row.Group -> VIEW_TYPE_GROUP
			is Row.Match -> VIEW_TYPE_MATCH
		}

	override fun onCreateViewHolder(
		parent: ViewGroup,
		viewType: Int,
	): ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		return when (viewType) {
			VIEW_TYPE_HEADER -> HeaderVH(LayoutSearchResultSectionBinding.inflate(inflater, parent, false))
			VIEW_TYPE_GROUP -> VH(LayoutSearchResultGroupBinding.inflate(inflater, parent, false))
			else -> ChildVH(LayoutSearchResultItemBinding.inflate(inflater, parent, false))
		}
	}

	override fun onBindViewHolder(
		holder: ViewHolder,
		position: Int,
	) {
		when (val row = rows[position]) {
			is Row.Header -> (holder as HeaderVH).binding.title.text = row.title
			is Row.Group -> bindGroup(holder as VH, row)
			is Row.Match -> bindMatch(holder as ChildVH, row.match)
		}
	}

	private fun bindGroup(
		holder: VH,
		row: Row.Group,
	) {
		val binding = holder.binding
		val file = row.file
		val color = binding.icon.context.resolveAttr(R.attr.colorPrimary)
		binding.title.text = file.name
		binding.icon.setImageResource(FileExtension.Factory.forFile(file, false).icon)
		binding.icon.setColorFilter(color, SRC_ATOP)
		binding.root.setOnClickListener { onFileClick(file) }
	}

	private fun bindMatch(
		holder: ChildVH,
		match: SearchResult,
	) {
		val text = holder.binding.text
		// Show the plain preview immediately; the tag guards the async highlight below
		// against landing on a recycled row.
		text.text = match.line
		text.tag = match
		CompletableFuture.runAsync {
			try {
				val scheme = SchemeAndroidIDE.newInstance(text.context)
				val sb = JavaHighlighter().highlight(scheme, match.line, match.match)
				ThreadUtils.runOnUiThread {
					if (text.tag === match) {
						text.text = sb
					}
				}
			} catch (e: Exception) {
				// The plain preview set above stays in place; only the highlight is lost.
				logger.warn("Failed to highlight search result preview", e)
			}
		}
		holder.binding.root.setOnClickListener { onMatchClick(match) }
	}

	override fun getItemCount(): Int = rows.size

	class VH(
		val binding: LayoutSearchResultGroupBinding,
	) : ViewHolder(binding.root)

	class ChildVH(
		val binding: LayoutSearchResultItemBinding,
	) : ViewHolder(binding.root)

	class HeaderVH(
		val binding: LayoutSearchResultSectionBinding,
	) : ViewHolder(binding.root)

	private sealed class Row {
		data class Header(
			val title: String,
		) : Row()

		data class Group(
			val file: File,
		) : Row()

		data class Match(
			val match: SearchResult,
		) : Row()
	}

	private companion object {
		val logger = LoggerFactory.getLogger(SearchListAdapter::class.java)

		const val VIEW_TYPE_HEADER = 0
		const val VIEW_TYPE_GROUP = 1
		const val VIEW_TYPE_MATCH = 2

		fun buildRows(
			sections: List<SearchResultSection>,
			keys: List<File>?,
		): List<Row> {
			return buildList {
				sections.forEach { section ->
					if (section.results.isEmpty()) return@forEach
					section.title?.let { add(Row.Header(it)) }
					val sectionKeys = keys ?: section.results.keys.toList()
					sectionKeys.forEach { file ->
						section.results[file]?.takeIf { it.isNotEmpty() }?.let { matches ->
							add(Row.Group(file))
							matches.forEach { match -> add(Row.Match(match)) }
						}
					}
				}
			}
		}
	}
}
