package com.itsaky.androidide.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.MessageStatus
import com.itsaky.androidide.agent.R
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.databinding.ListItemChatDiffBinding
import com.itsaky.androidide.agent.databinding.ListItemChatMessageBinding
import com.itsaky.androidide.agent.databinding.ListItemChatSystemMessageBinding
import com.itsaky.androidide.agent.diff.calculateDiffStats
import com.itsaky.androidide.agent.protocol.FileChange
import com.itsaky.androidide.agent.ui.ChatAdapter.DiffCallback.ACTION_EDIT
import com.itsaky.androidide.agent.util.splitLinesPreserveEnding
import io.noties.markwon.Markwon
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.path.pathString

class ChatAdapter(
    private val markwon: Markwon,
    private val onMessageAction: (action: String, message: ChatMessage) -> Unit
) :
    ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) {

    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val decimalSecondsFormatter = DecimalFormat("0.0")

    private val expandedMessageIds = mutableSetOf<String>()
    private val logger = LoggerFactory.getLogger(ChatAdapter::class.java)

    private object ExpansionPayload

    companion object {
        private const val VIEW_TYPE_DEFAULT = 0
        private const val VIEW_TYPE_SYSTEM = 1
        private const val VIEW_TYPE_DIFF = 2
    }

    sealed class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class DefaultMessageViewHolder(val binding: ListItemChatMessageBinding) :
        MessageViewHolder(binding.root)

    class SystemMessageViewHolder(val binding: ListItemChatSystemMessageBinding) :
        MessageViewHolder(binding.root)

    class DiffMessageViewHolder(val binding: ListItemChatDiffBinding) :
        MessageViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        if (message.sender == Sender.SYSTEM_DIFF || message.diffChanges != null) {
            return VIEW_TYPE_DIFF
        }
        return if (message.sender == Sender.SYSTEM && message.status == MessageStatus.ERROR) {
            VIEW_TYPE_DEFAULT
        } else if (message.sender == Sender.SYSTEM) {
            VIEW_TYPE_SYSTEM
        } else {
            VIEW_TYPE_DEFAULT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SYSTEM -> {
                val binding = ListItemChatSystemMessageBinding.inflate(inflater, parent, false)
                SystemMessageViewHolder(binding)
            }

            VIEW_TYPE_DIFF -> {
                val binding = ListItemChatDiffBinding.inflate(inflater, parent, false)
                DiffMessageViewHolder(binding)
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
            is DiffMessageViewHolder -> bindDiffMessage(holder, message)
        }
    }

    private fun bindDefaultMessage(holder: DefaultMessageViewHolder, message: ChatMessage) {
        holder.binding.messageSender.text = formatSenderLabel(message.sender)

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
                if (message.sender == Sender.SYSTEM) {
                    holder.binding.btnRetry.text =
                        holder.itemView.context.getString(R.string.open_ai_settings)
                    holder.binding.btnRetry.setIconResource(R.drawable.ic_settings)
                    holder.binding.btnRetry.setOnClickListener {
                        onMessageAction(DiffCallback.ACTION_OPEN_SETTINGS, message)
                    }
                } else {
                    holder.binding.btnRetry.text =
                        holder.itemView.context.getString(R.string.retry)
                    holder.binding.btnRetry.setIconResource(R.drawable.ic_refresh)
                    holder.binding.btnRetry.setOnClickListener {
                        onMessageAction(DiffCallback.ACTION_RETRY, message)
                    }
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

    private fun bindDiffMessage(holder: DiffMessageViewHolder, message: ChatMessage) {
        val changes = message.diffChanges
        val context = holder.itemView.context
        if (changes.isNullOrEmpty()) {
            holder.binding.diffHeaderText.text = message.text
            holder.binding.diffContentText.text = ""
            return
        }

        val stats = calculateDiffStats(changes)
        val additionColor = ContextCompat.getColor(context, R.color.diff_green)
        val removalColor = ContextCompat.getColor(context, R.color.diff_red)

        holder.binding.diffHeaderText.text = buildSpannedString {
            append("• ")
            val fileLabel = if (stats.fileCount == 1) "file" else "files"
            bold { append("Edited ${stats.fileCount} $fileLabel") }
            append(" (")
            color(additionColor) { append("+${stats.addedLines}") }
            append(' ')
            color(removalColor) { append("-${stats.removedLines}") }
            append(')')
        }

        holder.binding.diffContentText.text = buildDiffSpannable(changes, context)
    }

    private fun buildDiffSpannable(
        changes: Map<Path, FileChange>,
        context: Context
    ): SpannableString {
        val builder = SpannableStringBuilder()
        val additionColor = ContextCompat.getColor(context, R.color.diff_green)
        val removalColor = ContextCompat.getColor(context, R.color.diff_red)
        val headerColor = ContextCompat.getColor(context, R.color.diff_header)
        val metaColor = ContextCompat.getColor(context, R.color.diff_gutter)
        val hunkColor = ContextCompat.getColor(context, R.color.diff_hunk)

        val entries = changes.entries.toList()
        entries.forEachIndexed { index, (path, change) ->
            if (index != 0) {
                builder.append('\n')
            }
            val displayPath = path.pathString.ifEmpty { path.toString() }
            val headerStart = builder.length
            builder.append("  └ ")
            builder.append(displayPath)
            builder.append(" (")
            builder.append(diffDescriptor(change))
            builder.append(')')
            builder.append('\n')
            builder.setSpan(
                ForegroundColorSpan(headerColor),
                headerStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                headerStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            val diffLines = toDiffLines(path, change)
            diffLines.forEach { line ->
                val start = builder.length
                builder.append("    ")
                builder.append(line)
                val end = builder.length
                val span = when {
                    line.startsWith("@@") -> ForegroundColorSpan(hunkColor)
                    line.startsWith("+++") || line.startsWith("---") ->
                        ForegroundColorSpan(metaColor)

                    line.startsWith("+") && !line.startsWith("+++") ->
                        ForegroundColorSpan(additionColor)

                    line.startsWith("-") && !line.startsWith("---") ->
                        ForegroundColorSpan(removalColor)

                    else -> null
                }
                span?.let {
                    builder.setSpan(it, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                builder.append('\n')
            }
        }

        if (builder.isNotEmpty()) {
            builder.delete(builder.length - 1, builder.length)
        }

        return SpannableString(builder)
    }

    private fun diffDescriptor(change: FileChange): String {
        return when (change) {
            is FileChange.Add -> "created"
            is FileChange.Delete -> "deleted"
            is FileChange.Update -> "edited"
        }
    }

    private fun toDiffLines(path: Path, change: FileChange): List<String> {
        return when (change) {
            is FileChange.Update -> runCatching {
                splitDiffLines(change.unifiedDiff)
            }.onFailure { throwable ->
                logger.error(
                    "Failed to split diff lines for path {}. Falling back to error message.",
                    path,
                    throwable
                )
            }.getOrElse {
                listOf("[Failed to render diff: ${it.localizedMessage ?: "unknown error"}]")
            }

            is FileChange.Add -> buildAdditionDiffLines(path, change.content)
            is FileChange.Delete -> buildDeletionDiffLines(path, change.content)
        }
    }

    private fun buildAdditionDiffLines(path: Path, content: String): List<String> {
        val lines = runCatching { splitContentLines(content) }
            .onFailure { throwable ->
                logger.error(
                    "Failed to split addition content lines for path {}",
                    path,
                    throwable
                )
            }
            .getOrDefault(emptyList())
        val displayPath = path.pathString.ifEmpty { path.toString() }
        val header = listOf(
            "--- /dev/null",
            "+++ b/$displayPath",
            buildAdditionHunkHeader(lines.size)
        )
        val body = lines.map { "+$it" }
        return header + body
    }

    private fun buildDeletionDiffLines(path: Path, content: String): List<String> {
        val lines = runCatching { splitContentLines(content) }
            .onFailure { throwable ->
                logger.error(
                    "Failed to split deletion content lines for path {}",
                    path,
                    throwable
                )
            }
            .getOrDefault(emptyList())
        val displayPath = path.pathString.ifEmpty { path.toString() }
        val header = listOf(
            "--- a/$displayPath",
            "+++ /dev/null",
            buildDeletionHunkHeader(lines.size)
        )
        val body = lines.map { "-$it" }
        return header + body
    }

    private fun buildAdditionHunkHeader(lineCount: Int): String {
        val addSegment = if (lineCount == 0) "+0,0" else "+1,$lineCount"
        return "@@ -0,0 $addSegment @@"
    }

    private fun buildDeletionHunkHeader(lineCount: Int): String {
        val removeSegment = if (lineCount == 0) "-0,0" else "-1,$lineCount"
        return "@@ $removeSegment +0,0 @@"
    }

    private fun splitContentLines(content: String): List<String> {
        return content.splitLinesPreserveEnding()
    }

    private fun splitDiffLines(diff: String): List<String> {
        return diff.splitLinesPreserveEnding()
    }

    private fun formatSenderLabel(sender: Sender): String {
        return when (sender) {
            Sender.SYSTEM_DIFF -> "System"
            else -> sender.name.lowercase(Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
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
        const val ACTION_OPEN_SETTINGS = "open_settings"
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
