package com.itsaky.androidide.agent.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ApprovalId
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.ChatSession
import com.itsaky.androidide.agent.R
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.databinding.FragmentChatBinding
import com.itsaky.androidide.agent.model.ReviewDecision
import com.itsaky.androidide.agent.repository.allAgentTools
import com.itsaky.androidide.agent.viewmodel.ChatUiEvent
import com.itsaky.androidide.agent.tool.toJsonElement
import com.itsaky.androidide.agent.tool.toolJson
import com.itsaky.androidide.agent.ui.ChatAdapter
import com.itsaky.androidide.agent.ui.ChatAdapter.DiffCallback.ACTION_EDIT
import com.itsaky.androidide.agent.utils.ChatTranscriptUtils
import com.itsaky.androidide.agent.viewmodel.ChatViewModel
import com.itsaky.androidide.events.TokenUsageEvent
import com.itsaky.androidide.fragments.FragmentWithBinding
import com.itsaky.androidide.utils.FileShareUtils
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashInfo
import io.noties.markwon.Markwon
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatFragment :
    FragmentWithBinding<FragmentChatBinding>(FragmentChatBinding::inflate) {

    private val chatViewModel: ChatViewModel by activityViewModel()
    private val logger = LoggerFactory.getLogger(ChatFragment::class.java)

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
                    flashInfo(getString(R.string.agent_images_selected, uris.size))
                }
            }
    }

    override fun onResume() {
        super.onResume()
        chatViewModel.checkBackendStatusOnResume(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        markwon = Markwon.builder(requireContext())
            .usePlugin(LinkifyPlugin.create())
            .build()

        view.setOnApplyWindowInsetsListener(insetsListener)

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
                launch {
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
                launch {
                    chatViewModel.uiEvents.collect { event ->
                        when (event) {
                            is ChatUiEvent.EditMessage -> {
                                binding.promptInputEdittext.setText(event.text)
                                binding.promptInputEdittext.setSelection(event.text.length)
                                binding.promptInputEdittext.requestFocus()
                                val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE)
                                    as? InputMethodManager
                                imm?.showSoftInput(
                                    binding.promptInputEdittext,
                                    InputMethodManager.SHOW_IMPLICIT
                                )
                            }

                            ChatUiEvent.OpenSettings -> {
                                findNavController()
                                    .navigate(R.id.action_chatFragment_to_aiSettingsFragment)
                            }
                        }
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

    override fun onDestroyView() {
        view?.setOnApplyWindowInsetsListener(null)
        super.onDestroyView()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTokenUsageEvent(event: TokenUsageEvent) {
        binding.agentStatusContainer.isVisible = true
        val limit = event.tokenLimit
        if (limit <= 0) {
            binding.tokenUsageText.text = getString(R.string.agent_tokens_na)
            return
        }
        val percentage = (event.tokenCount.toFloat() / limit.toFloat() * 100).toInt()
        binding.tokenUsageText.text = getString(R.string.agent_tokens_percentage, percentage)
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
                val result = AgentDependencies.requireToolingApi()
                    .readFileContent(filePath, null, null)
                result.onSuccess { content ->
                    promptBuilder.append("--- START FILE: $filePath ---\n")
                    promptBuilder.append(content)
                    promptBuilder.append("\n--- END FILE: $filePath ---\n\n")
                }.onFailure { exception ->
                    logger.error("Failed to read context file: {}", filePath, exception)
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
                binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
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
        binding.btnTestTool.setOnClickListener {
            showToolSelectionDialog()
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

    private fun showToolSelectionDialog() {
        val toolNames =
            allAgentTools.flatMap { tool ->
                tool.functionDeclarations().orElse(emptyList()).map { decl ->
                    decl.name().get()
                }
            }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.agent_select_tool_to_test)
            .setItems(toolNames) { _, which ->
                val selectedToolName = toolNames[which]
                showArgumentInputDialog(selectedToolName)
            }
            .show()
    }

    private fun showArgumentInputDialog(toolName: String) {
        val tool = allAgentTools.flatMap { t ->
            t.functionDeclarations().orElse(emptyList())
        }.find { decl ->
            decl.name().get() == toolName
        }
        if (tool == null) {
            flashInfo(getString(R.string.agent_tool_not_found, toolName))
            return
        }

        val description = tool.description().orElse(getString(R.string.agent_tool_description_missing))
        val properties = tool.propertiesOrEmpty()
        val requiredFields = tool.requiredOrEmpty()

        if (properties.isEmpty()) {
            // No parameters needed, run directly
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.agent_run_tool_title, toolName))
                .setMessage(getString(R.string.agent_tool_no_params, description))
                .setPositiveButton(R.string.agent_dialog_run) { _, _ ->
                    chatViewModel.testTool(toolName, "{}")
                }
                .setNegativeButton(R.string.agent_dialog_cancel, null)
                .show()
            return
        }

        // Create a dynamic form with input fields
        val scrollView = ScrollView(requireContext())
        val paddingNormal = (16 * resources.displayMetrics.density).toInt()
        val paddingSmall = (8 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingNormal, paddingNormal, paddingNormal, paddingNormal)
        }

        // Add description
        container.addView(TextView(requireContext()).apply {
            text = description
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, 0, 0, paddingNormal)
        })

        // Map to store input fields
        val inputFields = mutableMapOf<String, android.widget.EditText>()
        val paramTypes = mutableMapOf<String, String>()

        // Create input field for each parameter
        properties.forEach { (paramName, schema) ->
            val paramType = schema.type().get().toString().lowercase()
            val paramDescription = schema.description().orElse("")
            val isRequired = requiredFields.contains(paramName)
            paramTypes[paramName] = paramType

            // Parameter label
            container.addView(TextView(requireContext()).apply {
                text = if (isRequired) "$paramName *" else paramName
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })

            // Parameter description
            if (paramDescription.isNotBlank()) {
                container.addView(TextView(requireContext()).apply {
                    text = paramDescription
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(android.graphics.Color.GRAY)
                })
            }

            // Input field
            val editText =
                TextInputEditText(requireContext()).apply {
                    hint = getDefaultHintForParameter(toolName, paramName, paramType)

                    // Set input type based on parameter type
                    inputType = when (paramType) {
                        "integer", "number" -> android.text.InputType.TYPE_CLASS_NUMBER
                        "boolean" -> android.text.InputType.TYPE_CLASS_TEXT
                        else -> android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    }

                    // Set default value if available
                    setText(getDefaultValueForParameter(toolName, paramName))

                    val layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = paddingNormal
                    }
                    this.layoutParams = layoutParams
                }

            val inputLayout =
                TextInputLayout(requireContext()).apply {
                    addView(editText)
                    val layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = paddingSmall
                    }
                    this.layoutParams = layoutParams
                }

            container.addView(inputLayout)
            inputFields[paramName] = editText
        }

        scrollView.addView(container)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.agent_test_tool_title, toolName))
            .setView(scrollView)
            .setPositiveButton(R.string.agent_dialog_run) { _, _ ->
                val args = buildArgumentsMap(inputFields, requiredFields, paramTypes)
                if (args != null) {
                    chatViewModel.testTool(toolName, args)
                } else {
                    flashError(getString(R.string.agent_required_fields_missing))
                }
            }
            .setNegativeButton(R.string.agent_dialog_cancel, null)
            .show()
    }

    private fun getDefaultHintForParameter(
        toolName: String,
        paramName: String,
        paramType: String
    ): String {
        val name = paramName.lowercase()
        return when {
            "path" in name -> getString(R.string.agent_hint_path_example)
            "content" in name -> getString(R.string.agent_hint_file_content)
            "pattern" in name -> getString(R.string.agent_hint_search_pattern)
            "offset" in name -> getString(R.string.agent_hint_offset)
            "limit" in name -> getString(R.string.agent_hint_limit)
            paramType == "boolean" -> getString(R.string.agent_hint_boolean)
            paramType == "integer" || paramType == "number" -> getString(R.string.agent_hint_number)
            else -> getString(R.string.agent_hint_param, paramName)
        }
    }

    private fun getDefaultValueForParameter(toolName: String, paramName: String): String {
        return when {
            paramName == "offset" -> "0"
            paramName == "limit" -> "1000"
            paramName == "file_path" && toolName == "read_file" -> {
                // Try to get current project path
                val projectDir =
                    com.itsaky.androidide.projects.IProjectManager.getInstance().projectDir
                projectDir?.path?.let { "$it/" } ?: ""
            }

            paramName == "path" -> {
                // Try to get current project path
                val projectDir =
                    com.itsaky.androidide.projects.IProjectManager.getInstance().projectDir
                projectDir?.path?.let { "$it/" } ?: ""
            }

            else -> ""
        }
    }

    private fun buildArgumentsMap(
        inputFields: Map<String, android.widget.EditText>,
        requiredFields: List<String>,
        paramTypes: Map<String, String>
    ): String? {
        val argsMap = mutableMapOf<String, Any>()

        for ((paramName, editText) in inputFields) {
            val value = editText.text.toString().trim()

            if (value.isEmpty()) {
                if (requiredFields.contains(paramName)) {
                    return null // Required field is empty
                }
                continue // Skip optional empty fields
            }

            // Try to parse as the appropriate type
            val paramType = paramTypes[paramName].orEmpty()
            argsMap[paramName] = parseValue(value, paramType)
        }

        return argsMapToJson(argsMap)
    }

    private fun argsMapToJson(argsMap: Map<String, Any>): String {
        val jsonElement: JsonElement = argsMap.toJsonElement()
        return toolJson.encodeToString(JsonElement.serializer(), jsonElement)
    }

    private fun parseValue(value: String, type: String): Any {
        val normalizedType = type.lowercase()
        return when (normalizedType) {
            "boolean" -> value.toBooleanStrictOrNull() ?: value
            "integer" -> value.toIntOrNull() ?: value
            "number" -> value.toDoubleOrNull() ?: value
            else -> value
        }
    }

    private fun ToolDeclaration.propertiesOrEmpty(): Map<String, Schema> =
        parameters().orElse(null)?.properties()?.orElse(emptyMap()).orEmpty()

    private fun ToolDeclaration.requiredOrEmpty(): List<String> =
        parameters().orElse(null)?.required()?.orElse(emptyList()).orEmpty()

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
            .setTitle(getString(R.string.agent_approval_title, state.toolName))
            .setMessage(message)
            .setPositiveButton(R.string.agent_approval_once) { _, _ ->
                chatViewModel.submitUserApproval(state.id, ReviewDecision.Approved)
            }
            .setNeutralButton(R.string.agent_approval_session) { _, _ ->
                chatViewModel.submitUserApproval(state.id, ReviewDecision.ApprovedForSession)
            }
            .setNegativeButton(R.string.agent_approval_deny) { _, _ ->
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
        try {
            ChatTranscriptUtils.shareTranscript(ctx, transcript)
        } catch (err: IOException) {
            logger.error("Failed to share chat transcript", err)
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
            val suffix = getString(R.string.agent_title_ellipsis_suffix)
            trimmed.take(maxLength - suffix.length).trimEnd() + suffix
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
        chatViewModel.onMessageAction(action, message)
    }
}
