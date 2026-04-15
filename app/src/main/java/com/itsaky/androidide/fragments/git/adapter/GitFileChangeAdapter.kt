package com.itsaky.androidide.fragments.git.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.ItemGitFileChangeBinding
import com.itsaky.androidide.git.core.models.ChangeType
import com.itsaky.androidide.git.core.models.FileChange

class GitFileChangeAdapter(
    private val onFileClicked: (FileChange) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : ListAdapter<FileChange, GitFileChangeAdapter.ViewHolder>(DiffCallback()) {

    // Keep track of which files are selected to be committed
    val selectedFiles = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGitFileChangeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val change = getItem(position)
        holder.bind(change)
    }

    inner class ViewHolder(private val binding: ItemGitFileChangeBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onFileClicked(getItem(pos))
                }
            }

            binding.checkbox.setOnCheckedChangeListener { _, isChecked ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val change = getItem(pos)
                    // Conflicted files should not be selectable
                    if (change.type == ChangeType.CONFLICTED) {
                        binding.checkbox.isChecked = false
                        return@setOnCheckedChangeListener
                    }
                    
                    if (isChecked) {
                        selectedFiles.add(change.path)
                    } else {
                        selectedFiles.remove(change.path)
                    }
                    onSelectionChanged(selectedFiles.size)
                }
            }
        }

        fun bind(change: FileChange) {
            binding.filePath.text = change.path

            val isConflicted = change.type == ChangeType.CONFLICTED
            binding.checkbox.isEnabled = !isConflicted
            
            // Ensure conflicted files are never in selectedFiles
            if (isConflicted) {
                selectedFiles.remove(change.path)
            }
            
            binding.checkbox.isChecked = selectedFiles.contains(change.path)
            
            // Dim the entry if it's conflicted to show it's disabled for staging
            binding.root.alpha = if (isConflicted) 0.5f else 1.0f

            val (imageRes, descRes) = when (change.type) {
                ChangeType.ADDED -> R.drawable.ic_file_added to R.string.desc_file_added
                ChangeType.MODIFIED -> R.drawable.ic_file_modified to R.string.desc_file_modified
                ChangeType.DELETED -> R.drawable.ic_file_deleted to R.string.desc_file_deleted
                ChangeType.UNTRACKED -> R.drawable.ic_file_added to R.string.desc_file_untracked
                ChangeType.RENAMED -> R.drawable.ic_file_renamed to R.string.desc_file_renamed
                ChangeType.CONFLICTED -> R.drawable.ic_file_conflicted to R.string.desc_file_conflicted
            }
            binding.statusIcon.apply {
                setImageResource(imageRes)
                contentDescription = binding.root.context.getString(descRes)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<FileChange>() {
        override fun areItemsTheSame(oldItem: FileChange, newItem: FileChange): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: FileChange, newItem: FileChange): Boolean {
            return oldItem == newItem
        }
    }
}
