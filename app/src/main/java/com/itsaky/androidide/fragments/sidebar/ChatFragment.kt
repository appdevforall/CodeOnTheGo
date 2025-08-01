package com.itsaky.androidide.fragments.sidebar

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.sidebar.adapter.ChatAdapter
import com.itsaky.androidide.api.commands.ReadFileCommand
import com.itsaky.androidide.databinding.FragmentChatBinding
import com.itsaky.androidide.fragments.EmptyStateFragment
import com.itsaky.androidide.models.AgentState
import com.itsaky.androidide.models.ChatMessage
import com.itsaky.androidide.utils.flashInfo
import com.itsaky.androidide.viewmodel.ChatViewModel
import io.noties.markwon.Markwon
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class ChatFragment :
    EmptyStateFragment<FragmentChatBinding>(FragmentChatBinding::inflate) {

    private val chatViewModel: ChatViewModel by activityViewModel()

    private lateinit var chatAdapter: ChatAdapter
    private val selectedContext = mutableListOf<String>()
    private val selectedImageUris = mutableListOf<Uri>()
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>
    private lateinit var markwon: Markwon

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyStateViewModel.emptyMessage.value = "No git actions yet"
        emptyStateViewModel.isEmpty.value = false
        markwon = Markwon.builder(requireContext())
            .usePlugin(LinkifyPlugin.create())
            .build()

        setupUI()
        setupListeners()
        setupStateObservers()
        chatViewModel.currentSession.observe(viewLifecycleOwner, Observer { session ->
            session?.let {
                chatAdapter.submitList(it.messages.toList())
                updateUIState(it.messages)
                binding.chatRecyclerView.post {
                    binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                }
            }
        })
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

    private fun handleSendMessage() {
        val inputText = binding.promptInputEdittext.text.toString().trim()
        if (inputText.isEmpty()) {
            return
        }

        binding.promptInputEdittext.text?.clear()

        viewLifecycleOwner.lifecycleScope.launch {
            if (selectedContext.isEmpty()) {
                chatViewModel.sendMessage(inputText)
            } else {
                val masterPrompt = buildMasterPrompt(inputText)
                chatViewModel.sendMessage(fullPrompt = masterPrompt, originalUserText = inputText)
            }
        }
    }

    private suspend fun buildMasterPrompt(userInput: String): String = withContext(Dispatchers.IO) {
        val promptBuilder = StringBuilder()
        promptBuilder.append("this is the master prompt, answer to the user message: $userInput\n")

        if (selectedContext.isNotEmpty()) {
            promptBuilder.append("\nuse the context:\n")
            selectedContext.forEach { filePath ->
                val result = ReadFileCommand(filePath).execute()
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
        })

        binding.chatRecyclerView.adapter = chatAdapter
        binding.chatRecyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        val modes = arrayOf("Agent", "Ask", "Manual")
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, modes)
        binding.agentModeAutocomplete.setAdapter(adapter)
    }

    private fun setupListeners() {
        binding.promptInputEdittext.doAfterTextChanged { text ->
            binding.btnSendPrompt.isEnabled = !text.isNullOrBlank()
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

                else -> false
            }
        }
    }

    private fun setupStateObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.agentState.collect { state ->
                when (state) {
                    is AgentState.Idle -> {
                        // Hide status, enable input, show send button
                        binding.agentStatusText.isVisible = false
                        binding.promptInputLayout.isEnabled = true
                        binding.btnSendPrompt.visibility = View.VISIBLE
                        binding.btnStopGeneration.visibility = View.GONE
                    }

                    is AgentState.Processing -> {
                        // Show status with message, disable input, show stop button
                        binding.agentStatusText.text = state.message
                        binding.agentStatusText.isVisible = true
                        binding.promptInputLayout.isEnabled = false
                        binding.btnSendPrompt.visibility = View.GONE
                        binding.btnStopGeneration.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun updateUIState(messages: List<ChatMessage>) {
        val hasMessages = messages.isNotEmpty()
        binding.emptyChatView.isVisible = !hasMessages
        binding.chatRecyclerView.isVisible = hasMessages
    }

    override fun onPause() {
        super.onPause()
        chatViewModel.saveSessions(requireActivity().getPreferences(Context.MODE_PRIVATE))
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
            ChatAdapter.ACTION_EDIT -> {
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
            // You can add more actions here in the future
        }
    }
}