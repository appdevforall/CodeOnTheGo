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

package org.appdevforall.codeonthego.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.appdevforall.codeonthego.adapters.RunTasksListAdapter.VH
import com.itsaky.androidide.databinding.LayoutRunTaskItemBinding
import org.appdevforall.codeonthego.idetooltips.TooltipManager
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import org.appdevforall.codeonthego.models.Checkable
import org.appdevforall.codeonthego.project.GradleModels

/**
 * Adapter for showing tasks list in [RunTaskDialogFragment]
 * [org.appdevforall.codeonthego.fragments.RunTasksDialogFragment].
 *
 * @author Akash Yadav
 */
class RunTasksListAdapter
	@JvmOverloads
	constructor(
		tasks: List<Checkable<GradleModels.GradleTask>>,
		val onCheckChanged: (Checkable<GradleModels.GradleTask>) -> Unit = {},
	) : FilterableRecyclerViewAdapter<VH, Checkable<GradleModels.GradleTask>>(tasks) {
		data class VH(
			val binding: LayoutRunTaskItemBinding,
		) : RecyclerView.ViewHolder(binding.root)

		override fun onCreateViewHolder(
			parent: ViewGroup,
			viewType: Int,
		): VH =
			VH(
				LayoutRunTaskItemBinding.inflate(
					LayoutInflater.from(parent.context),
					parent,
					false,
				),
			)

		override fun onBindViewHolder(
			holder: VH,
			position: Int,
		) {
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
				TooltipManager.showIdeCategoryTooltip(
					context = binding.root.context,
					anchorView = binding.root,
					tag = TooltipTag.gradleTaskTooltipTag(task.path),
				)
				true
			}
		}

		override fun getQueryCandidate(item: Checkable<GradleModels.GradleTask>): String = item.data.path
	}
