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
import com.itsaky.androidide.actions.sidebar.adapter.ChatAdapter.DiffCallback.ACTION_EDIT
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.MessageStatus
import com.itsaky.androidide.databinding.ListItemChatMessageBinding
import com.itsaky.androidide.databinding.ListItemChatSystemMessageBinding
import io.noties.markwon.Markwon
import java.util.Locale

class ChatAdapter(
    private val markwon: Markwon,
    private val onMessageAction: (action: String, message: ChatMessage) -> Unit
) :
    ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) { // ✨ Use generic ViewHolder

    // ✨ 1. Define constants for our view types
    companion object {
        private const val VIEW_TYPE_DEFAULT = 0
        private const val VIEW_TYPE_SYSTEM = 1
    }

    // ✨ 2. Create a sealed class for our different ViewHolders
    sealed class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class DefaultMessageViewHolder(val binding: ListItemChatMessageBinding) :
        MessageViewHolder(binding.root)

    class SystemMessageViewHolder(val binding: ListItemChatSystemMessageBinding) :
        MessageViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).sender) {
            ChatMessage.Sender.SYSTEM -> VIEW_TYPE_SYSTEM
            else -> VIEW_TYPE_DEFAULT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SYSTEM -> {
                val binding = ListItemChatSystemMessageBinding.inflate(inflater, parent, false)
                SystemMessageViewHolder(binding)
            }

            else -> { // VIEW_TYPE_DEFAULT
                val binding = ListItemChatMessageBinding.inflate(inflater, parent, false)
                DefaultMessageViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        // ✨ 5. Bind data based on the type of holder
        when (holder) {
            is DefaultMessageViewHolder -> bindDefaultMessage(holder, message)
            is SystemMessageViewHolder -> bindSystemMessage(holder, message)
        }
    }

    private fun bindDefaultMessage(holder: DefaultMessageViewHolder, message: ChatMessage) {
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
            MessageStatus.SENT, MessageStatus.COMPLETED -> {
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
                    onMessageAction(DiffCallback.ACTION_RETRY, message)
                }
            }
        }
    }

    private fun bindSystemMessage(holder: SystemMessageViewHolder, message: ChatMessage) {
        // Simple title, you can make this more dynamic if you want
        holder.binding.messageHeaderTitle.text = "System Log"
        markwon.setMarkdown(holder.binding.messageContent, message.text)

        // Set initial state based on the data model
        if (message.isExpanded) {
            holder.binding.messageContent.visibility = View.VISIBLE
            holder.binding.expandIcon.rotation = 180f
        } else {
            holder.binding.messageContent.visibility = View.GONE
            holder.binding.expandIcon.rotation = 0f
        }

        // Handle click to expand/collapse
        holder.binding.messageHeader.setOnClickListener {
            // Toggle the state in the data model
            message.isExpanded = !message.isExpanded
            notifyItemChanged(holder.adapterPosition, Unit) // Re-bind with animation
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

    object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        const val ACTION_EDIT = "edit"
        const val ACTION_RETRY = "retry"
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            // Include isExpanded in the comparison
            return oldItem == newItem
        }
    }
}