package com.itsaky.androidide.plugins.aiassistant.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.plugins.aiassistant.R
import com.itsaky.androidide.plugins.aiassistant.models.ChatMessage
import com.itsaky.androidide.plugins.aiassistant.models.MessageStatus
import com.itsaky.androidide.plugins.aiassistant.models.Sender
import io.noties.markwon.Markwon
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val pluginContext: Context,
    private val markwon: Markwon,
    private val onMessageAction: (action: String, message: ChatMessage) -> Unit
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) {

    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val decimalSecondsFormatter = DecimalFormat("0.0")
    private val expandedMessageIds = mutableSetOf<String>()

    companion object {
        private const val VIEW_TYPE_DEFAULT = 0
        private const val VIEW_TYPE_SYSTEM = 1

        const val ACTION_EDIT = "edit"
        const val ACTION_RETRY = "retry"
        const val ACTION_OPEN_SETTINGS = "open_settings"
    }

    sealed class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class DefaultMessageViewHolder(view: View) : MessageViewHolder(view) {
        val messageSender: TextView = view.findViewById(R.id.message_sender)
        val loadingIndicator: ProgressBar = view.findViewById(R.id.loading_indicator)
        val messageContent: TextView = view.findViewById(R.id.message_content)
        val messageMetadataContainer: LinearLayout = view.findViewById(R.id.message_metadata_container)
        val messageTimestamp: TextView = view.findViewById(R.id.message_timestamp)
        val messageDuration: TextView = view.findViewById(R.id.message_duration)
        val btnRetry: Button = view.findViewById(R.id.btn_retry)
    }

    class SystemMessageViewHolder(view: View) : MessageViewHolder(view) {
        val messageHeader: LinearLayout = view.findViewById(R.id.message_header)
        val messageHeaderTitle: TextView = view.findViewById(R.id.message_header_title)
        val expandIcon: ImageView = view.findViewById(R.id.expand_icon)
        val messageContent: TextView = view.findViewById(R.id.message_content)
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return if (message.sender == Sender.SYSTEM && message.status == MessageStatus.ERROR) {
            VIEW_TYPE_DEFAULT
        } else if (message.sender == Sender.SYSTEM) {
            VIEW_TYPE_SYSTEM
        } else {
            VIEW_TYPE_DEFAULT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        // Use plugin context for inflating layouts to access plugin resources
        val inflater = LayoutInflater.from(pluginContext)
        return when (viewType) {
            VIEW_TYPE_SYSTEM -> {
                val view = inflater.inflate(R.layout.list_item_chat_system_message, parent, false)
                SystemMessageViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.list_item_chat_message, parent, false)
                DefaultMessageViewHolder(view)
            }
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
        holder.messageSender.text = message.sender.name.lowercase(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

        holder.itemView.setOnLongClickListener { view ->
            if (message.status == MessageStatus.SENT) {
                showContextMenu(view, message)
            }
            true
        }

        when (message.status) {
            MessageStatus.LOADING -> {
                holder.loadingIndicator.visibility = View.VISIBLE
                holder.messageContent.visibility = View.GONE
                holder.btnRetry.visibility = View.GONE
                holder.messageMetadataContainer.visibility = View.GONE
            }
            MessageStatus.SENT, MessageStatus.COMPLETED -> {
                holder.loadingIndicator.visibility = View.GONE
                holder.messageContent.visibility = View.VISIBLE
                holder.btnRetry.visibility = View.GONE
                markwon.setMarkdown(holder.messageContent, message.text)
                updateMessageMetadata(holder, message)
            }
            MessageStatus.ERROR -> {
                holder.loadingIndicator.visibility = View.GONE
                holder.messageContent.visibility = View.VISIBLE
                holder.btnRetry.visibility = View.VISIBLE
                holder.messageContent.text = message.text
                if (message.sender == Sender.SYSTEM) {
                    holder.btnRetry.text = "Open AI Settings"
                    holder.btnRetry.setOnClickListener {
                        onMessageAction(ACTION_OPEN_SETTINGS, message)
                    }
                } else {
                    holder.btnRetry.text = "Retry"
                    holder.btnRetry.setOnClickListener {
                        onMessageAction(ACTION_RETRY, message)
                    }
                }
                updateMessageMetadata(holder, message)
            }
        }
    }

    private fun bindSystemMessage(holder: SystemMessageViewHolder, message: ChatMessage) {
        markwon.setMarkdown(holder.messageContent, message.text)
        updateSystemMessageExpansion(holder, message)

        holder.messageHeader.setOnClickListener {
            if (!expandedMessageIds.remove(message.id)) {
                expandedMessageIds.add(message.id)
            }
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                notifyItemChanged(pos)
            }
        }
    }

    private fun updateSystemMessageExpansion(holder: SystemMessageViewHolder, message: ChatMessage) {
        val isExpanded = expandedMessageIds.contains(message.id)
        if (isExpanded) {
            holder.messageHeaderTitle.text = "System Log"
            holder.messageContent.visibility = View.VISIBLE
            holder.expandIcon.rotation = 180f
        } else {
            holder.messageHeaderTitle.text = createPreview(message.text)
            holder.messageContent.visibility = View.GONE
            holder.expandIcon.rotation = 0f
        }
    }

    private fun createPreview(rawText: String): String {
        val cleanedText = rawText
            .replace(Regex("`{1,3}|\\*{1,2}|_"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return "Log: $cleanedText"
    }

    private fun updateMessageMetadata(holder: DefaultMessageViewHolder, message: ChatMessage) {
        val timestampText = formatTimestamp(message.timestamp)
        val durationText = formatDuration(message.durationMs)

        val hasTimestamp = timestampText != null
        val hasDuration = durationText != null

        if (!hasTimestamp && !hasDuration) {
            holder.messageMetadataContainer.visibility = View.GONE
            return
        }

        holder.messageMetadataContainer.visibility = View.VISIBLE

        if (hasTimestamp) {
            holder.messageTimestamp.text = timestampText
            holder.messageTimestamp.visibility = View.VISIBLE
        } else {
            holder.messageTimestamp.visibility = View.GONE
        }

        if (hasDuration) {
            holder.messageDuration.text = durationText
            holder.messageDuration.visibility = View.VISIBLE
        } else {
            holder.messageDuration.visibility = View.GONE
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

        popup.menu.add(0, 1, 0, "Copy Text")
        if (message.sender == Sender.USER) {
            popup.menu.add(0, 2, 0, "Edit Message")
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("chat_message", message.text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    onMessageAction(ACTION_EDIT, message)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onCurrentListChanged(previousList: MutableList<ChatMessage>, currentList: MutableList<ChatMessage>) {
        super.onCurrentListChanged(previousList, currentList)
        expandedMessageIds.clear()
    }

    object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
