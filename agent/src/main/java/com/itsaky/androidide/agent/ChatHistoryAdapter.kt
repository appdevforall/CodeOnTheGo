package com.itsaky.androidide.agent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.agent.databinding.ListItemChatHistoryBinding

class ChatHistoryAdapter(
    private val sessions: List<ChatSession>,
    private val onSessionClick: (ChatSession) -> Unit
) : RecyclerView.Adapter<ChatHistoryAdapter.ViewHolder>() {

    class ViewHolder(val binding: ListItemChatHistoryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ListItemChatHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = sessions.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessions[position]
        holder.binding.chatTitle.text = session.title
        holder.binding.chatDate.text = session.formattedDate
        holder.itemView.setOnClickListener { onSessionClick(session) }
    }
}
