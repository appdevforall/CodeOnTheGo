package com.itsaky.androidide.fragments.git.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.ItemGitCommitBinding
import com.itsaky.androidide.git.core.models.GitCommit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GitCommitHistoryAdapter :
    ListAdapter<GitCommit, GitCommitHistoryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGitCommitBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val commit = getItem(position)
        holder.bind(commit)
    }

    class ViewHolder(private val binding: ItemGitCommitBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        fun bind(commit: GitCommit) {
            binding.apply {
                tvCommitMessage.text = commit.message
                tvCommitAuthor.text = commit.authorName
                tvCommitTime.text = dateFormat.format(Date(commit.timestamp))
                imgCommitStatus.setImageResource(if (commit.hasBeenPushed) R.drawable.ic_cloud_done else R.drawable.ic_upload)
                imgCommitStatus.contentDescription = if (commit.hasBeenPushed) {
                    binding.root.context.getString(R.string.commit_pushed)
                } else {
                    binding.root.context.getString(R.string.commit_not_pushed)
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<GitCommit>() {
        override fun areItemsTheSame(oldItem: GitCommit, newItem: GitCommit): Boolean {
            return oldItem.hash == newItem.hash
        }

        override fun areContentsTheSame(oldItem: GitCommit, newItem: GitCommit): Boolean {
            return oldItem == newItem
        }
    }

}
