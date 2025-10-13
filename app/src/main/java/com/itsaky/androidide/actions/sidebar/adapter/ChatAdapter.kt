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
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.databinding.ListItemChatMessageBinding
import com.itsaky.androidide.databinding.ListItemChatSystemMessageBinding
import io.noties.markwon.Markwon
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val markwon: Markwon,
    private val onMessageAction: (action: String, message: ChatMessage) -> Unit
) :
    ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) {

    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val decimalSecondsFormatter = DecimalFormat("0.0")

    private val expandedMessageIds = mutableSetOf<String>()

    private object ExpansionPayload

    companion object {
        private const val VIEW_TYPE_DEFAULT = 0
        private const val VIEW_TYPE_SYSTEM = 1
    }

    sealed class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class DefaultMessageViewHolder(val binding: ListItemChatMessageBinding) :
        MessageViewHolder(binding.root)

    class SystemMessageViewHolder(val binding: ListItemChatSystemMessageBinding) :
        MessageViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).sender) {
            Sender.SYSTEM -> VIEW_TYPE_SYSTEM
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

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(ExpansionPayload)) {
            // Partial bind: only update the expansion UI
            if (holder is SystemMessageViewHolder) {
                updateSystemMessageExpansion(holder, getItem(position))
            }
        } else {
            // Full bind
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
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
                holder.binding.messageMetadataContainer.visibility = View.GONE
            }
            MessageStatus.SENT, MessageStatus.COMPLETED -> {
                holder.binding.loadingIndicator.visibility = View.GONE
                holder.binding.messageContent.visibility = View.VISIBLE
                holder.binding.btnRetry.visibility = View.GONE
                markwon.setMarkdown(holder.binding.messageContent, message.text)
                updateMessageMetadata(holder.binding, message)
            }
            MessageStatus.ERROR -> {
                holder.binding.loadingIndicator.visibility = View.GONE
                holder.binding.messageContent.visibility = View.VISIBLE
                holder.binding.btnRetry.visibility = View.VISIBLE
                holder.binding.messageContent.text = message.text
                holder.binding.btnRetry.setOnClickListener {
                    onMessageAction(DiffCallback.ACTION_RETRY, message)
                }
                updateMessageMetadata(holder.binding, message)
            }
        }
    }

    private fun bindSystemMessage(holder: SystemMessageViewHolder, message: ChatMessage) {
        // Full bind: always process the markdown content
        markwon.setMarkdown(holder.binding.messageContent, message.text)

        // Set the current expansion state
        updateSystemMessageExpansion(holder, message)

        // Handle click to expand/collapse
        holder.binding.messageHeader.setOnClickListener {
            // Update the internal state set
            if (!expandedMessageIds.remove(message.id)) {
                expandedMessageIds.add(message.id)
            }
            // Notify the adapter with the specific payload for an efficient update
            notifyItemChanged(holder.bindingAdapterPosition, ExpansionPayload)
        }
    }

    /**
     * A new helper function that only updates the views related to the expansion state.
     * This is called for both full and partial binds.
     */
    private fun updateSystemMessageExpansion(
        holder: SystemMessageViewHolder,
        message: ChatMessage
    ) {
        val isExpanded = expandedMessageIds.contains(message.id)
        if (isExpanded) {
            holder.binding.messageHeaderTitle.text = "System Log"
            holder.binding.messageContent.visibility = View.VISIBLE
            holder.binding.expandIcon.rotation = 180f
        } else {
            holder.binding.messageHeaderTitle.text = createPreview(message.text)
            holder.binding.messageContent.visibility = View.GONE
            holder.binding.expandIcon.rotation = 0f
        }
    }

    private fun createPreview(rawText: String): String {
        val cleanedText = rawText
            .replace(Regex("`{1,3}|\\*{1,2}|_"), "") // Remove common markdown characters
            .replace(Regex("\\s+"), " ") // Collapse all whitespace and newlines into a single space
            .trim()
        return "Log: $cleanedText" // Prepend a label for context
    }

    private fun updateMessageMetadata(
        binding: ListItemChatMessageBinding,
        message: ChatMessage
    ) {
        val timestampText = formatTimestamp(message.timestamp)
        val durationText = formatDuration(message.durationMs)

        val hasTimestamp = timestampText != null
        val hasDuration = durationText != null

        if (!hasTimestamp && !hasDuration) {
            binding.messageMetadataContainer.visibility = View.GONE
            return
        }

        binding.messageMetadataContainer.visibility = View.VISIBLE

        if (hasTimestamp) {
            binding.messageTimestamp.text = timestampText
            binding.messageTimestamp.visibility = View.VISIBLE
        } else {
            binding.messageTimestamp.visibility = View.GONE
        }

        if (hasDuration) {
            binding.messageDuration.text = durationText
            binding.messageDuration.visibility = View.VISIBLE
        } else {
            binding.messageDuration.visibility = View.GONE
        }
    }

    private fun formatTimestamp(timestamp: Long): String? {
        if (timestamp <= 0L) return null
        return synchronized(timeFormatter) {
            timeFormatter.format(Date(timestamp))
        }
    }

    private fun formatDuration(durationMs: Long?): String? {
        if (durationMs == null || durationMs <= 0) return null
        val seconds = durationMs / 1000.0
        return if (seconds < 60) {
            "took ${decimalSecondsFormatter.format(seconds)}s"
        } else {
            val minutes = seconds / 60.0
            "took ${decimalSecondsFormatter.format(minutes)}m"
        }
    }


    private fun showContextMenu(view: View, message: ChatMessage) {
        val context = view.context
        val popup = PopupMenu(context, view)
        popup.inflate(R.menu.chat_message_context_menu)

        val editItem = popup.menu.findItem(R.id.menu_edit_message)
        editItem.isVisible = message.sender == Sender.USER

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
            return oldItem == newItem
        }
    }
}
