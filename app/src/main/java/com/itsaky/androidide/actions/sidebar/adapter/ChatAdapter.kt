package com.itsaky.androidide.actions.sidebar.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.actions.sidebar.models.ChatMessage
import com.itsaky.androidide.databinding.ListItemChatMessageBinding
import io.noties.markwon.Markwon
import java.util.Locale

class ChatAdapter(private val markwon: Markwon) :
    ListAdapter<ChatMessage, ChatAdapter.MessageViewHolder>(DiffCallback) {

    class MessageViewHolder(val binding: ListItemChatMessageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ListItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = getItem(position)
        holder.binding.messageSender.text = message.sender.name.lowercase(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

        markwon.setMarkdown(holder.binding.messageContent, message.text)
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.text == newItem.text
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}