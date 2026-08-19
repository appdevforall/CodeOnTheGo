package com.itsaky.androidide.fragments.git.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.ItemGitBranchBinding
import com.itsaky.androidide.git.core.models.GitBranch

sealed class GitBranchListItem {
	data class Header(
		val title: String,
	) : GitBranchListItem()

	data class BranchItem(
		val branch: GitBranch,
		val displayName: String,
	) : GitBranchListItem()
}

class GitBranchAdapter(
	private val onBranchSelected: (GitBranch) -> Unit,
	private val onMergeClicked: ((GitBranch) -> Unit)? = null,
) : ListAdapter<GitBranchListItem, RecyclerView.ViewHolder>(DiffCallback) {
	companion object {
		private const val VIEW_TYPE_HEADER = 0
		private const val VIEW_TYPE_BRANCH = 1
	}

	override fun getItemViewType(position: Int): Int =
		when (getItem(position)) {
			is GitBranchListItem.Header -> VIEW_TYPE_HEADER
			is GitBranchListItem.BranchItem -> VIEW_TYPE_BRANCH
		}

	override fun onCreateViewHolder(
		parent: ViewGroup,
		viewType: Int,
	): RecyclerView.ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		return if (viewType == VIEW_TYPE_HEADER) {
			val view = inflater.inflate(R.layout.item_git_branch_header, parent, false)
			HeaderViewHolder(view)
		} else {
			val binding = ItemGitBranchBinding.inflate(inflater, parent, false)
			BranchViewHolder(binding)
		}
	}

	override fun onBindViewHolder(
		holder: RecyclerView.ViewHolder,
		position: Int,
	) {
		when (val item = getItem(position)) {
			is GitBranchListItem.Header -> (holder as HeaderViewHolder).bind(item)
			is GitBranchListItem.BranchItem -> (holder as BranchViewHolder).bind(item)
		}
	}

	inner class HeaderViewHolder(
		itemView: View,
	) : RecyclerView.ViewHolder(itemView) {
		private val tvTitle: TextView = itemView.findViewById(R.id.tvHeaderTitle)

		fun bind(item: GitBranchListItem.Header) {
			tvTitle.text = item.title
		}
	}

	inner class BranchViewHolder(
		private val binding: ItemGitBranchBinding,
	) : RecyclerView.ViewHolder(binding.root) {
		fun bind(item: GitBranchListItem.BranchItem) {
			binding.tvBranchName.text = item.displayName

			if (item.branch.isCurrent) {
				binding.ivActiveCheck.visibility = View.VISIBLE
				binding.imgBranchIcon.visibility = View.GONE
				binding.btnMergeAction.visibility = View.GONE
			} else {
				binding.ivActiveCheck.visibility = View.GONE
				binding.imgBranchIcon.visibility = View.VISIBLE
				binding.btnMergeAction.visibility = if (onMergeClicked != null) View.VISIBLE else View.GONE
			}

			binding.btnMergeAction.setOnClickListener {
				onMergeClicked?.invoke(item.branch)
			}

			binding.root.setOnClickListener {
				onBranchSelected(item.branch)
			}
		}
	}

	private object DiffCallback : DiffUtil.ItemCallback<GitBranchListItem>() {
		override fun areItemsTheSame(
			oldItem: GitBranchListItem,
			newItem: GitBranchListItem,
		): Boolean =
			when {
				oldItem is GitBranchListItem.Header && newItem is GitBranchListItem.Header -> {
					oldItem.title == newItem.title
				}

				oldItem is GitBranchListItem.BranchItem && newItem is GitBranchListItem.BranchItem -> {
					oldItem.branch.fullName == newItem.branch.fullName
				}

				else -> {
					false
				}
			}

		override fun areContentsTheSame(
			oldItem: GitBranchListItem,
			newItem: GitBranchListItem,
		): Boolean = oldItem == newItem
	}
}
