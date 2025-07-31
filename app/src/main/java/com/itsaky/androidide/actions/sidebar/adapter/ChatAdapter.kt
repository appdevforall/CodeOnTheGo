package com.itsaky.androidide.actions.sidebar.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.ListItemChatMessageBinding
import com.itsaky.androidide.models.ChatMessage
import com.itsaky.androidide.models.MessageStatus
import io.noties.markwon.Markwon
import java.util.Locale

class ChatAdapter(
    private val markwon: Markwon,
    private val onMessageAction: (action: String, message: ChatMessage) -> Unit
) :
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

        holder.itemView.setOnLongClickListener { view ->
            if (message.status == MessageStatus.SENT) {
                showContextMenu(view, message)
            }
            true
        }

        when (message.status) {
            MessageStatus.LOADING -> {
                holder.binding.loadingIndicator.visibility = View.VISIBLE
                holder.binding.messageContent.visibility = View.GONE
                holder.binding.btnRetry.visibility = View.GONE
            }

            MessageStatus.SENT -> {
                holder.binding.loadingIndicator.visibility = View.GONE
                holder.binding.messageContent.visibility = View.VISIBLE
                holder.binding.btnRetry.visibility = View.GONE
                markwon.setMarkdown(holder.binding.messageContent, message.text)
            }

            MessageStatus.ERROR -> {
                holder.binding.loadingIndicator.visibility = View.GONE
                holder.binding.messageContent.visibility = View.VISIBLE
                holder.binding.btnRetry.visibility = View.VISIBLE
                holder.binding.messageContent.text = message.text
                holder.binding.btnRetry.setOnClickListener {
                    onMessageAction(ACTION_RETRY, message)
                }
            }
        }
    }

    private fun showContextMenu(view: View, message: ChatMessage) {
        val context = view.context
        val popup = PopupMenu(context, view)
        popup.inflate(R.menu.chat_message_context_menu)

        val editItem = popup.menu.findItem(R.id.menu_edit_message)
        editItem.isVisible = message.sender == ChatMessage.Sender.USER

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_copy_text -> {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("chat_message", message.text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    true
                }

                R.id.menu_edit_message -> {
                    onMessageAction(ACTION_EDIT, message)
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        const val ACTION_EDIT = "edit"
        const val ACTION_RETRY = "retry"

        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.text == newItem.text
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}