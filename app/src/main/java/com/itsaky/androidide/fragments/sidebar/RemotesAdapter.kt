package com.itsaky.androidide.fragments.sidebar

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.databinding.ListItemRemoteBinding

class RemotesAdapter(
    private val remotes: MutableList<GitRemote>,
    private val onRemoteClick: (GitRemote) -> Unit
) : RecyclerView.Adapter<RemotesAdapter.ViewHolder>() {

    class ViewHolder(val binding: ListItemRemoteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ListItemRemoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val remote = remotes[position]
        holder.binding.remoteNameTextView.text = remote.name
        holder.binding.remoteUrlTextView.text = remote.url
        holder.itemView.setOnClickListener { onRemoteClick(remote) }
    }

    override fun getItemCount() = remotes.size

    fun getRemoteAt(position: Int): GitRemote = remotes[position]

    fun removeAt(position: Int) {
        remotes.removeAt(position)
        notifyItemRemoved(position)
    }
}