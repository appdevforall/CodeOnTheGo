package com.itsaky.androidide.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.databinding.ListItemChangedFileBinding
import com.itsaky.androidide.models.GitFileStatus

class GitStatusAdapter(private val files: List<GitFileStatus>) :
    RecyclerView.Adapter<GitStatusAdapter.ViewHolder>() {

    class ViewHolder(val binding: ListItemChangedFileBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ListItemChangedFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val fileStatus = files[position]
        holder.binding.filePathTextView.text = fileStatus.filePath
        holder.binding.fileStatusTextView.text = fileStatus.status

        // Set checkbox state without triggering the listener
        holder.binding.fileCheckbox.setOnCheckedChangeListener(null)
        holder.binding.fileCheckbox.isChecked = fileStatus.isChecked

        // Listen for user clicks on the checkbox
        holder.binding.fileCheckbox.setOnCheckedChangeListener { _, isChecked ->
            fileStatus.isChecked = isChecked
        }
    }

    override fun getItemCount() = files.size

    // Helper function to get the list of files the user has checked
    fun getStagedFiles(): List<String> {
        return files.filter { it.isChecked }.map { it.filePath }
    }
}