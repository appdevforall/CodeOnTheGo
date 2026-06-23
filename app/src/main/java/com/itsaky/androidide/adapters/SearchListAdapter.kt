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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.blankj.utilcode.util.ThreadUtils
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.SearchListAdapter.VH
import com.itsaky.androidide.databinding.LayoutSearchResultGroupBinding
import com.itsaky.androidide.databinding.LayoutSearchResultItemBinding
import com.itsaky.androidide.models.FileExtension
import com.itsaky.androidide.models.SearchResult
import com.itsaky.androidide.syntax.colorschemes.SchemeAndroidIDE
import com.itsaky.androidide.syntax.highlighters.JavaHighlighter
import com.itsaky.androidide.utils.resolveAttr
import java.io.File
import java.util.concurrent.CompletableFuture

class SearchListAdapter(
  private val results: Map<File, List<SearchResult>?>,
  private val onFileClick: (File) -> Unit,
  private val onMatchClick: (SearchResult) -> Unit,
  private val keys: List<File>
) : Adapter<VH>() {

  constructor(
    results: Map<File, List<SearchResult>?>,
    onFileClick: (File) -> Unit,
    onMatchClick: (SearchResult) -> Unit
  ) : this(results, onFileClick, onMatchClick, results.keys.toList())

  override fun onCreateViewHolder(p1: ViewGroup, p2: Int): VH {
    return VH(LayoutSearchResultGroupBinding.inflate(LayoutInflater.from(p1.context)))
  }

  override fun onBindViewHolder(p1: VH, p2: Int) {
    val binding = p1.binding
    val file = keys[p2]
    val matches = results[file] ?: listOf()
    android.util.Log.d("SearchListAdapter", "onBindViewHolder: file=${file.name}, matches=${matches.size}")
    val color = binding.icon.context.resolveAttr(R.attr.colorPrimary)
    binding.title.text = file.name
    binding.icon.setImageResource(FileExtension.Factory.forFile(file, false).icon)
    binding.icon.setColorFilter(color, SRC_ATOP)
    binding.items.layoutManager = LinearLayoutManager(binding.items.context)
    binding.items.adapter = ChildAdapter(matches)
    android.util.Log.d("SearchListAdapter", "ChildAdapter created with ${matches.size} items")
    binding.root.setOnClickListener { onFileClick(file) }
  }

  override fun getItemCount(): Int {
    return results.size
  }

  inner class ChildAdapter(val matches: List<SearchResult>) : Adapter<ChildVH>() {

    override fun onCreateViewHolder(p1: ViewGroup, p2: Int): ChildVH {
      return ChildVH(LayoutSearchResultItemBinding.inflate(LayoutInflater.from(p1.context)))
    }

    override fun onBindViewHolder(p1: ChildVH, p2: Int) {
      val match = matches[p2]
      val binding = p1.binding
      android.util.Log.d("SearchListAdapter", "ChildAdapter onBindViewHolder: index=$p2, similarity=${match.similarity}, line=${match.line}")

      // Show score + line range for vector results. Keyword results have no score, so
      // the badge container must be GONE (not just empty), else the match_parent text
      // collapses against a 0-width anchor in the horizontal scroll parent.
      if (match.similarity != null) {
        binding.badgeContainer.visibility = android.view.View.VISIBLE
        binding.similarityBadge.visibility = android.view.View.VISIBLE
        val percentage = (match.similarity * 100).toInt()
        binding.similarityBadge.text = "$percentage%"

        binding.lineRange.text = match.line
        binding.lineRange.visibility =
          if (match.line.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
      } else {
        binding.badgeContainer.visibility = android.view.View.GONE
        binding.similarityBadge.visibility = android.view.View.GONE
        binding.lineRange.visibility = android.view.View.GONE
      }

      CompletableFuture.runAsync {
        try {
          val scheme = SchemeAndroidIDE.newInstance(binding.text.context)
          val sb = JavaHighlighter().highlight(scheme, match.line, match.match)
          ThreadUtils.runOnUiThread { binding.text.text = sb }
        } catch (e: Exception) {
          ThreadUtils.runOnUiThread { binding.text.text = match.match }
        }
      }
      binding.root.setOnClickListener { onMatchClick(match) }
    }

    override fun getItemCount(): Int {
      return matches.size
    }
  }

  class VH(val binding: LayoutSearchResultGroupBinding) : ViewHolder(binding.root)
  class ChildVH(val binding: LayoutSearchResultItemBinding) : ViewHolder(binding.root)
}
