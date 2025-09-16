package com.itsaky.androidide.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.databinding.ListItemCommitBinding
import org.eclipse.jgit.revwalk.RevCommit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GitHistoryAdapter(
    private val commits: List<RevCommit>,
    private val onCommitClick: (RevCommit) -> Unit
) : RecyclerView.Adapter<GitHistoryAdapter.ViewHolder>() {

    class ViewHolder(val binding: ListItemCommitBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ListItemCommitBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val commit = commits[position]
        val author = commit.authorIdent
        val date = Date(author.getWhen().time)
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        holder.binding.commitMessageTextView.text = commit.shortMessage
        holder.binding.commitAuthorTextView.text = author.name
        holder.binding.commitDateTextView.text = dateFormat.format(date)
        holder.itemView.setOnClickListener { onCommitClick(commit) }
    }

    override fun getItemCount() = commits.size
}