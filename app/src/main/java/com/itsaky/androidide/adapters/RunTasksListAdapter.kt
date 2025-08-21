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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.adapters.RunTasksListAdapter.VH
import com.itsaky.androidide.databinding.LayoutRunTaskItemBinding
import com.itsaky.androidide.idetooltips.IDETooltipItem
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.models.Checkable
import com.itsaky.androidide.tooling.api.models.GradleTask
import com.itsaky.androidide.viewmodel.RunTasksViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Adapter for showing tasks list in [RunTaskDialogFragment]
 * [com.itsaky.androidide.fragments.RunTasksDialogFragment].
 *
 * @author Akash Yadav
 */
class RunTasksListAdapter
@JvmOverloads
constructor(
  tasks: List<Checkable<GradleTask>>,
  val onCheckChanged: (Checkable<GradleTask>) -> Unit = {},
  private val viewModel: RunTasksViewModel? = null
) : FilterableRecyclerViewAdapter<VH, Checkable<GradleTask>>(tasks) {

  data class VH(val binding: LayoutRunTaskItemBinding) : RecyclerView.ViewHolder(binding.root)

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
    return VH(LayoutRunTaskItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
  }

  override fun onBindViewHolder(holder: VH, position: Int) {
    val binding = holder.binding
    val data = getItem(position)
    val task = data.data

    binding.check.isChecked = data.isChecked
    binding.taskPath.text = task.path
    binding.taskDesc.text = task.description

    binding.root.setOnClickListener {
      data.isChecked = !data.isChecked
      binding.check.isChecked = data.isChecked
      onCheckChanged(data)
    }

    binding.root.setOnLongClickListener {
      CoroutineScope(Dispatchers.Main).launch {
        val item = TooltipManager.getTooltip(
          context = binding.root.context,
          category = "project.gradle.tasks",
          tag = task.name
        )
        item?.let { tooltipData ->
          TooltipManager.showIDETooltip(
            binding.root.context,
            binding.root,
            0,
            IDETooltipItem(
              tooltipCategory = "project.gradle.tasks",
              tooltipTag = tooltipData.tooltipTag,
              detail = tooltipData.detail,
              summary = tooltipData.summary,
              buttons = tooltipData.buttons,
            ),
            { _, url, title ->
              viewModel?.navigateToHelp(url, title)
            }
          )
        }
      }
      true
    }
  }


  override fun getQueryCandidate(item: Checkable<GradleTask>): String {
    return item.data.path
  }
}
