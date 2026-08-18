package com.itsaky.androidide.fragments.git.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.databinding.ItemGitBranchBinding
import com.itsaky.androidide.git.core.models.GitBranch

class GitBranchAdapter(
    private val onBranchSelected: (GitBranch) -> Unit
) : ListAdapter<GitBranch, GitBranchAdapter.BranchViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BranchViewHolder {
        val binding = ItemGitBranchBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BranchViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BranchViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BranchViewHolder(private val binding: ItemGitBranchBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(branch: GitBranch) {
            binding.tvBranchName.text = branch.name
            binding.tvRemoteBadge.visibility = if (branch.isRemote) View.VISIBLE else View.GONE
            binding.ivActiveCheck.visibility = if (branch.isCurrent) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                onBranchSelected(branch)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<GitBranch>() {
        override fun areItemsTheSame(oldItem: GitBranch, newItem: GitBranch): Boolean {
            return oldItem.fullName == newItem.fullName
        }

        override fun areContentsTheSame(oldItem: GitBranch, newItem: GitBranch): Boolean {
            return oldItem == newItem
        }
    }
}
