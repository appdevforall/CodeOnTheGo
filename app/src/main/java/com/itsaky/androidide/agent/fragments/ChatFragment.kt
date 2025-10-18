package com.itsaky.androidide.agent.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.sidebar.adapter.ChatAdapter
import com.itsaky.androidide.actions.sidebar.adapter.ChatAdapter.DiffCallback.ACTION_EDIT
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ApprovalId
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.ChatSession
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.model.ReviewDecision
import com.itsaky.androidide.agent.tool.toJsonElement
import com.itsaky.androidide.agent.tool.toolJson
import com.itsaky.androidide.agent.viewmodel.ChatViewModel
import com.itsaky.androidide.api.commands.ReadFileCommand
import com.itsaky.androidide.databinding.FragmentChatBinding
import com.itsaky.androidide.events.TokenUsageEvent
import com.itsaky.androidide.fragments.EmptyStateFragment
import com.itsaky.androidide.utils.IntentUtils
import com.itsaky.androidide.utils.flashInfo
import io.noties.markwon.Markwon
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatFragment :
    EmptyStateFragment<FragmentChatBinding>(FragmentChatBinding::inflate) {

    private val chatViewModel: ChatViewModel by activityViewModel()

    private val insetsListener = View.OnApplyWindowInsetsListener { _, insets ->
        if (isAdded) {
            val insetsCompat = WindowInsetsCompat.toWindowInsetsCompat(insets)

            // Check if the on-screen keyboard is visible
            val isImeVisible = insetsCompat.isVisible(WindowInsetsCompat.Type.ime())

            // Get the height of the IME space
            val imeHeight = insetsCompat.getInsets(WindowInsetsCompat.Type.ime()).bottom

            // Only apply the height if the software keyboard is visible
            binding.keyboardSpacer.updateLayoutParams {
                height = if (isImeVisible) imeHeight else 0
            }
        }
        insets
    }

    private lateinit var chatAdapter: ChatAdapter
    private val selectedContext = mutableListOf<String>()
    private val selectedImageUris = mutableListOf<Uri>()
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>
    private lateinit var markwon: Markwon
    private var lastRenderedMessages: List<ChatMessage> = emptyList()
    private var approvalDialog: AlertDialog? = null
    private var currentApprovalId: ApprovalId? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (chatViewModel.sessions.value.isNullOrEmpty()) {
            chatViewModel.loadSessions(requireActivity().getPreferences(Context.MODE_PRIVATE))
        }
        imagePickerLauncher =
            registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    selectedImageUris.addAll(uris)
                    updateContextChips()
                    flashInfo("${uris.size} images selected.")
                }
            }
    }

    override fun onResume() {
        super.onResume()
        activity?.window?.decorView?.setOnApplyWindowInsetsListener(insetsListener)
        chatViewModel.checkBackendStatusOnResume(requireContext())
    }

    override fun onFragmentLongPressed() {

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        EventBus.getDefault().register(this)
        emptyStateViewModel.emptyMessage.value = "No git actions yet"
        emptyStateViewModel.isEmpty.value = false
        markwon = Markwon.builder(requireContext())
            .usePlugin(LinkifyPlugin.create())
            .build()


        setupUI()
        setupListeners()
        setupStateObservers()

        chatViewModel.backendStatus.observe(viewLifecycleOwner) { status ->
            binding.backendStatusText.text = status.displayText
        }
        chatViewModel.currentSession.observe(viewLifecycleOwner) { session ->
            updateToolbarForSession(session)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.chatMessages.collect { messages ->
                    lastRenderedMessages = messages
                    chatAdapter.submitList(messages)
                    updateUIState(messages)
                    updateToolbarForMessages(messages)
                    if (messages.isNotEmpty()) {
                        binding.chatRecyclerView.scrollToPosition(messages.size - 1)
                    }
                }
            }
        }
        parentFragmentManager.setFragmentResultListener(
            "chat_history_request",
            viewLifecycleOwner
        ) { _, bundle ->
            bundle.getString("selected_session_id")?.let { sessionId ->
                chatViewModel.setCurrentSession(sessionId)
            }
        }
        parentFragmentManager.setFragmentResultListener(
            "context_selection_request", viewLifecycleOwner
        ) { _, bundle ->
            bundle.getStringArrayList("selected_context")?.let { result ->
                selectedContext.clear()
                selectedContext.addAll(result)
                updateContextChips()
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTokenUsageEvent(event: TokenUsageEvent) {
        binding.agentStatusContainer.isVisible = true
        val percentage = (event.tokenCount.toFloat() / event.tokenLimit.toFloat() * 100).toInt()
        binding.tokenUsageText.text = "Tokens: $percentage%"
    }

    private fun handleSendMessage() {
        val inputText = binding.promptInputEdittext.text.toString().trim()
        if (inputText.isEmpty()) {
            return
        }

        binding.promptInputEdittext.text?.clear()

        viewLifecycleOwner.lifecycleScope.launch {
            if (selectedContext.isEmpty()) {
                chatViewModel.sendMessage(inputText, inputText, requireContext())
            } else {
                val masterPrompt = buildMasterPrompt(inputText)
                chatViewModel.sendMessage(
                    fullPrompt = masterPrompt,
                    originalUserText = inputText,
                    requireContext()
                )
            }
        }
    }

    private suspend fun buildMasterPrompt(userInput: String): String = withContext(Dispatchers.IO) {
        val promptBuilder = StringBuilder()
        promptBuilder.append("this is the master prompt, answer to the user message: $userInput\n")

        if (selectedContext.isNotEmpty()) {
            promptBuilder.append("\nuse the context:\n")
            selectedContext.forEach { filePath ->
                val result = ReadFileCommand(filePath, null, null).execute()
                result.onSuccess { content ->
                    promptBuilder.append("--- START FILE: $filePath ---\n")
                    promptBuilder.append(content)
                    promptBuilder.append("\n--- END FILE: $filePath ---\n\n")
                }.onFailure { exception ->
                    Log.e("ChatFragment", "Failed to read context file: $filePath", exception)
                    promptBuilder.append("--- FAILED TO READ FILE: $filePath ---\n\n")
                }
            }
        }

        // Clear the selected context on the main thread after using it
        withContext(Dispatchers.Main) {
            selectedContext.clear()
            updateContextChips()
        }

        promptBuilder.toString()
    }

    private fun setupUI() {
        chatAdapter = ChatAdapter(markwon) { action, message ->
            handleMessageAction(action, message)
        }
        chatAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            }

            override fun onChanged() {
                super.onChanged()
                chatAdapter.notifyDataSetChanged()
            }
        })

        binding.chatRecyclerView.adapter = chatAdapter
        binding.chatRecyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
    }

    private fun setupListeners() {
        binding.promptInputEdittext.doAfterTextChanged { text ->
            val currentState = chatViewModel.agentState.value
            if (!isAgentBusy(currentState)) {
                binding.btnSendPrompt.isEnabled = !text.isNullOrBlank()
            }
        }
        binding.btnSendPrompt.setOnClickListener {
            handleSendMessage()
        }
        binding.btnStopGeneration.setOnClickListener {
            chatViewModel.stopAgentResponse()
        }
        binding.promptInputEdittext.setOnKeyListener { _, keyCode, keyEvent ->
            if (keyEvent.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                if (keyEvent.isShiftPressed) {
                    false // Allow newline
                } else {
                    handleSendMessage()
                    true // Consume event
                }
            } else {
                false
            }
        }
        binding.btnAddContext.setOnClickListener {
            findNavController().navigate(R.id.action_chatFragment_to_contextSelectionFragment)
        }
        binding.btnUploadImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }
        binding.chatToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_new_chat -> {
                    chatViewModel.createNewSession()
                    true
                }

                R.id.menu_chat_history -> {
                    findNavController().navigate(R.id.action_chatFragment_to_chatHistoryFragment)
                    true
                }

                R.id.menu_copy_chat -> showCopyChatOptions()

                R.id.menu_ai_settings -> {
                    findNavController().navigate(R.id.action_chatFragment_to_aiSettingsFragment)
                    true
                }

                else -> false
            }
        }
        binding.promptInputEdittext.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) {
                val imm =
                    context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
    }

    private fun setupStateObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.agentState.combine(chatViewModel.stepElapsedTime) { state, stepTime ->
                state to stepTime
            }.combine(chatViewModel.totalElapsedTime) { (state, stepTime), totalTime ->
                Triple(state, stepTime, totalTime)
            }.collect { (state, stepTime, totalTime) ->
                if (state !is AgentState.AwaitingApproval) {
                    dismissApprovalDialog()
                }
                when (state) {
                    AgentState.Idle -> {
                        binding.agentStatusContainer.isVisible = false

                        binding.btnStopGeneration.isVisible = false
                        binding.btnSendPrompt.isVisible = true

                        binding.btnSendPrompt.isEnabled =
                            binding.promptInputEdittext.text?.isNotBlank() == true
                    }

                    is AgentState.Initializing -> {
                        binding.agentStatusMessage.text = state.message
                        binding.agentStatusTimer.isVisible = false
                        binding.agentStatusContainer.isVisible = true

                        binding.btnStopGeneration.isVisible = true
                        binding.btnStopGeneration.isEnabled = true
                        binding.btnSendPrompt.isVisible = false
                    }

                    is AgentState.Thinking -> {
                        binding.agentStatusMessage.text = state.thought
                        binding.agentStatusTimer.isVisible = false
                        binding.agentStatusContainer.isVisible = true

                        binding.btnStopGeneration.isVisible = true
                        binding.btnStopGeneration.isEnabled = true
                        binding.btnSendPrompt.isVisible = false
                    }

                    is AgentState.Executing -> {
                        val stepIndex = state.currentStepIndex
                        val totalSteps = state.plan.steps.size
                        val description =
                            state.plan.steps.getOrNull(stepIndex)?.description ?: "Working..."
                        val stepTimeFormatted = chatViewModel.formatTime(stepTime)
                        val totalTimeFormatted = chatViewModel.formatTime(totalTime)
                        val timeString = "($stepTimeFormatted of $totalTimeFormatted)"

                        binding.agentStatusMessage.text =
                            "Step ${stepIndex + 1} of $totalSteps: $description"
                        binding.agentStatusTimer.text = timeString
                        binding.agentStatusTimer.isVisible = true
                        binding.agentStatusContainer.isVisible = true

                        binding.btnStopGeneration.isVisible = true
                        binding.btnStopGeneration.isEnabled = true
                        binding.btnSendPrompt.isVisible = false
                    }

                    is AgentState.AwaitingApproval -> {
                        binding.agentStatusMessage.text = state.reason
                        binding.agentStatusTimer.isVisible = false
                        binding.agentStatusContainer.isVisible = true

                        binding.btnStopGeneration.isVisible = false
                        binding.btnSendPrompt.isVisible = true
                        binding.btnSendPrompt.isEnabled = true

                        showApprovalDialog(state)
                    }

                    is AgentState.Error -> {
                        binding.agentStatusMessage.text = state.message
                        binding.agentStatusTimer.isVisible = false
                        binding.agentStatusContainer.isVisible = true

                        binding.btnStopGeneration.isVisible = false
                        binding.btnSendPrompt.isVisible = true

                        binding.btnSendPrompt.isEnabled =
                            binding.promptInputEdittext.text?.isNotBlank() == true
                    }
                }
            }
        }
    }

    private fun showApprovalDialog(state: AgentState.AwaitingApproval) {
        if (currentApprovalId == state.id && approvalDialog?.isShowing == true) {
            return
        }

        dismissApprovalDialog()
        currentApprovalId = state.id

        val argsText = formatToolArgs(state.toolArgs)
        val message = buildString {
            append(state.reason)
            append("\n\nArguments:\n")
            append(argsText)
        }

        approvalDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Approve '${state.toolName}'?")
            .setMessage(message)
            .setPositiveButton("Approve Once") { _, _ ->
                chatViewModel.submitUserApproval(state.id, ReviewDecision.Approved)
            }
            .setNeutralButton("Approve for Session") { _, _ ->
                chatViewModel.submitUserApproval(state.id, ReviewDecision.ApprovedForSession)
            }
            .setNegativeButton("Deny") { _, _ ->
                chatViewModel.submitUserApproval(state.id, ReviewDecision.Denied)
            }
            .setOnCancelListener {
                chatViewModel.submitUserApproval(state.id, ReviewDecision.Denied)
            }
            .create().apply {
                setOnDismissListener {
                    approvalDialog = null
                    currentApprovalId = null
                }
                show()
            }
    }

    private fun dismissApprovalDialog() {
        approvalDialog?.setOnDismissListener(null)
        approvalDialog?.dismiss()
        approvalDialog = null
        currentApprovalId = null
    }

    private fun formatToolArgs(args: Map<String, Any?>): String {
        if (args.isEmpty()) {
            return "{}"
        }
        val jsonElement: JsonElement = args.toJsonElement()
        return toolJson.encodeToString(JsonElement.serializer(), jsonElement)
    }

    override fun onDestroyView() {
        dismissApprovalDialog()
        EventBus.getDefault().unregister(this)
        super.onDestroyView()
    }

    private fun updateUIState(messages: List<ChatMessage>) {
        val hasMessages = messages.isNotEmpty()
        binding.emptyChatView.isVisible = !hasMessages
        if (!hasMessages) {
            val sessionTitle = chatViewModel.currentSession.value?.title
            binding.emptyChatView.text =
                sessionTitle?.takeIf { it.isNotBlank() } ?: getString(R.string.new_chat)
        }
        binding.chatRecyclerView.isVisible = hasMessages
    }

    private fun updateToolbarForSession(session: ChatSession?) {
        updateToolbar(
            sessionTitle = session?.title,
            messages = lastRenderedMessages
        )
    }

    private fun updateToolbarForMessages(messages: List<ChatMessage>) {
        updateToolbar(
            sessionTitle = chatViewModel.currentSession.value?.title,
            messages = messages
        )
    }

    private fun isAgentBusy(state: AgentState): Boolean {
        return when (state) {
            AgentState.Idle -> false
            is AgentState.Error -> false
            is AgentState.Initializing,
            is AgentState.Thinking,
            is AgentState.Executing,
            is AgentState.AwaitingApproval -> true
        }
    }

    private fun updateToolbar(sessionTitle: String?, messages: List<ChatMessage>) {
        val title = when {
            messages.isNotEmpty() -> {
                val firstUserMessage = messages.firstOrNull { it.sender == Sender.USER }?.text
                val fallback = firstUserMessage ?: messages.first().text
                formatChatTitle(fallback)
            }

            !sessionTitle.isNullOrBlank() -> formatChatTitle(sessionTitle)

            else -> getString(R.string.new_chat)
        }
        binding.chatToolbar.title = title

        val menuItem = binding.chatToolbar.menu.findItem(R.id.menu_new_chat)
        val isNewChatDisplayed = messages.isEmpty()
        menuItem?.isEnabled = !isNewChatDisplayed
        val alpha = if (!isNewChatDisplayed) 255 else (255 * 0.4f).toInt()
        menuItem?.icon?.mutate()?.alpha = alpha
    }

    private fun showCopyChatOptions(): Boolean {
        val ctx = context ?: return false
        val transcript = buildChatTranscript() ?: return true

        val options = arrayOf(
            getString(R.string.copy_chat_option_copy),
            getString(R.string.copy_chat_option_share)
        )

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.copy_chat)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyTranscriptToClipboard(ctx, transcript)
                    1 -> shareTranscriptAsText(ctx, transcript)
                }
            }
            .show()
        return true
    }

    private fun buildChatTranscript(): String? {
        if (lastRenderedMessages.isEmpty()) {
            flashInfo(getString(R.string.copy_chat_empty))
            return null
        }

        val transcript = lastRenderedMessages.joinToString(separator = "\n\n") { message ->
            val senderLabel = when (message.sender) {
                Sender.USER -> getString(R.string.copy_chat_sender_user)
                Sender.AGENT -> getString(R.string.copy_chat_sender_agent)
                Sender.SYSTEM, Sender.SYSTEM_DIFF -> getString(R.string.copy_chat_sender_system)
                Sender.TOOL -> getString(R.string.copy_chat_sender_tool)
            }
            val body = message.text.trim().ifEmpty {
                getString(R.string.copy_chat_empty_message_placeholder)
            }
            "$senderLabel:\n$body"
        }.trim()

        if (transcript.isEmpty()) {
            flashInfo(getString(R.string.copy_chat_empty))
            return null
        }
        return transcript
    }

    private fun copyTranscriptToClipboard(ctx: Context, transcript: String) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            flashInfo(getString(R.string.copy_chat_failed))
            return
        }

        val clip = ClipData.newPlainText(
            getString(R.string.copy_chat_clip_label),
            transcript
        )
        clipboard.setPrimaryClip(clip)
        flashInfo(getString(R.string.copy_chat_success))
    }

    private fun shareTranscriptAsText(ctx: Context, transcript: String) {
        val exportsDir = File(ctx.cacheDir, "chat_exports")
        if (!exportsDir.exists() && !exportsDir.mkdirs()) {
            flashInfo(getString(R.string.copy_chat_share_failed))
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(exportsDir, "chat-transcript-$timestamp.txt")

        try {
            file.writeText(transcript, Charsets.UTF_8)
            IntentUtils.shareFile(ctx, file, "text/plain")
        } catch (err: IOException) {
            Log.e("ChatFragment", "Failed to share chat transcript", err)
            flashInfo(getString(R.string.copy_chat_share_failed))
        }
    }

    private fun formatChatTitle(rawTitle: String?): String {
        if (rawTitle.isNullOrBlank()) {
            return getString(R.string.new_chat)
        }
        val trimmed = rawTitle.trim()
        val maxLength = 48
        return if (trimmed.length <= maxLength) {
            trimmed
        } else {
            trimmed.take(maxLength - 3).trimEnd() + "..."
        }
    }


    override fun onPause() {
        super.onPause()
        // Clean up the listener to prevent leaks or unwanted behavior
        activity?.window?.decorView?.setOnApplyWindowInsetsListener(null)
        chatViewModel.saveAllSessionsAndState(requireActivity().getPreferences(Context.MODE_PRIVATE))
    }

    private fun updateContextChips() {
        binding.contextChipGroup.removeAllViews()
        val allContextItems =
            selectedContext + selectedImageUris.map { "Image: ${it.lastPathSegment}" }
        if (allContextItems.isEmpty()) {
            binding.contextChipGroup.visibility = View.GONE
        } else {
            binding.contextChipGroup.visibility = View.VISIBLE
            allContextItems.forEach { itemText ->
                val chip = Chip(requireContext()).apply {
                    text = itemText
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        val uriToRemove =
                            selectedImageUris.find { "Image: ${it.lastPathSegment}" == itemText }
                        if (uriToRemove != null) {
                            selectedImageUris.remove(uriToRemove)
                        } else {
                            selectedContext.remove(itemText)
                        }
                        updateContextChips() // Refresh the chips
                    }
                }
                binding.contextChipGroup.addView(chip)
            }
        }
    }

    private fun handleMessageAction(action: String, message: ChatMessage) {
        when (action) {
            ACTION_EDIT -> {
                // Set the selected message's text into the input field
                binding.promptInputEdittext.setText(message.text)

                // Move the cursor to the end of the text for a better editing experience
                binding.promptInputEdittext.setSelection(message.text.length)

                // Request focus on the input field and show the keyboard
                binding.promptInputEdittext.requestFocus()
                val imm =
                    context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(binding.promptInputEdittext, InputMethodManager.SHOW_IMPLICIT)
            }

            ChatAdapter.DiffCallback.ACTION_OPEN_SETTINGS -> {
                findNavController().navigate(R.id.action_chatFragment_to_aiSettingsFragment)
            }
        }
    }
}
