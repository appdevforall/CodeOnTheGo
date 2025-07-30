package com.itsaky.androidide.fragments.sidebar

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.sidebar.adapter.ChatAdapter
import com.itsaky.androidide.actions.sidebar.models.ChatMessage
import com.itsaky.androidide.databinding.FragmentChatBinding
import com.itsaky.androidide.fragments.EmptyStateFragment
import com.itsaky.androidide.viewmodel.ChatViewModel
import java.util.Locale

class ChatFragment :
    EmptyStateFragment<FragmentChatBinding>(FragmentChatBinding::inflate) {

    private val chatViewModel by viewModels<ChatViewModel>(
        ownerProducer = { requireActivity() }
    )

    private lateinit var chatAdapter: ChatAdapter
    private val messageHistory = mutableListOf<ChatMessage>()
    private val gson = Gson()

    companion object {
        private const val CHAT_HISTORY_PREF_KEY = "chat_history_v1"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyStateViewModel.emptyMessage.value = "No git actions yet"
        emptyStateViewModel.isEmpty.value = false
        setupUI()
        setupListeners()
        loadChatHistory()
        updateUIState()
    }

    private fun setupUI() {
        // Setup RecyclerView
        chatAdapter = ChatAdapter(messageHistory)
        binding.chatRecyclerView.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true // New items appear at the bottom
            }
        }

        // Setup Agent Mode Dropdown
        val modes = arrayOf("Agent Mode", "Ask Mode", "Manual Mode")
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, modes)
        binding.agentModeAutocomplete.setAdapter(adapter)
    }

    private fun setupListeners() {
        // Enable/disable send button based on input
        binding.promptInputEdittext.doAfterTextChanged { text ->
            binding.btnSendPrompt.isEnabled = !text.isNullOrBlank()
        }

        // Handle send button click
        binding.btnSendPrompt.setOnClickListener {
            val inputText = binding.promptInputEdittext.text.toString().trim()
            if (inputText.isNotEmpty()) {
                sendMessage(inputText)
            }
        }

        // TODO: Add listeners for other buttons
        // binding.btn_add_context.setOnClickListener { ... }
        // binding.btn_upload_image.setOnClickListener { ... }
    }

    private fun sendMessage(text: String) {
        // Add user message to the list
        val userMessage = ChatMessage(text, ChatMessage.Sender.USER)
        addMessageToList(userMessage)

        // Clear the input field and disable send button
        binding.promptInputEdittext.text?.clear()
        binding.btnSendPrompt.isEnabled = false

        // Simulate AI response
        simulateAgentResponse(text)
    }

    private fun simulateAgentResponse(originalText: String) {
        // Show typing indicator or progress bar (optional)

        // Simulate a delay
        Handler(Looper.getMainLooper()).postDelayed({
            val agentResponse = ChatMessage(
                text = originalText.uppercase(Locale.getDefault()),
                sender = ChatMessage.Sender.AGENT
            )
            addMessageToList(agentResponse)
        }, 1500) // 1.5-second delay
    }

    private fun addMessageToList(message: ChatMessage) {
        chatAdapter.addMessage(message)
        binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        updateUIState()
        saveChatHistory()
    }

    private fun updateUIState() {
        val hasMessages = messageHistory.isNotEmpty()
        binding.emptyChatView.isVisible = !hasMessages
        binding.chatRecyclerView.isVisible = hasMessages
    }

    private fun saveChatHistory() {
        val json = gson.toJson(messageHistory)
        val prefs = requireActivity().getPreferences(Context.MODE_PRIVATE)
        prefs.edit().putString(CHAT_HISTORY_PREF_KEY, json).apply()
    }

    private fun loadChatHistory() {
        val prefs = requireActivity().getPreferences(Context.MODE_PRIVATE)
        val json = prefs.getString(CHAT_HISTORY_PREF_KEY, null)
        if (json != null) {
            val type = object : TypeToken<MutableList<ChatMessage>>() {}.type
            val savedMessages: MutableList<ChatMessage> = gson.fromJson(json, type)
            messageHistory.addAll(savedMessages)
            chatAdapter.notifyDataSetChanged()
        }
    }
}