package com.itsaky.androidide.fragments.sidebar

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.sidebar.adapter.ChatAdapter
import com.itsaky.androidide.actions.sidebar.models.ChatMessage
import com.itsaky.androidide.databinding.FragmentChatBinding
import com.itsaky.androidide.fragments.EmptyStateFragment
import com.itsaky.androidide.utils.flashInfo
import com.itsaky.androidide.viewmodel.ChatViewModel

class ChatFragment :
    EmptyStateFragment<FragmentChatBinding>(FragmentChatBinding::inflate) {

    // Use activityViewModels to share the ViewModel
    private val chatViewModel: ChatViewModel by activityViewModels()

    private lateinit var chatAdapter: ChatAdapter
    private val selectedContext = mutableListOf<String>()
    private val selectedImageUris = mutableListOf<Uri>()
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load sessions only if they haven't been loaded yet
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
        setupUI()
        setupListeners()

        chatViewModel.currentSession.observe(viewLifecycleOwner, Observer { session ->
            session?.let {
                // Use submitList to efficiently update the RecyclerView with animations
                chatAdapter.submitList(it.messages.toList()) // Submit a copy of the list

                updateUIState(it.messages)

                // Scroll to the bottom after the list has been updated
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

        parentFragmentManager.setFragmentResultListener("context_selection_request", viewLifecycleOwner) { _, bundle ->
            bundle.getStringArrayList("selected_context")?.let { result ->
                selectedContext.clear()
                selectedContext.addAll(result)
                updateContextChips()
            }
        }
    }

    private fun setupUI() {
        chatAdapter = ChatAdapter()
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

    private fun handleSendMessage() {
        val inputText = binding.promptInputEdittext.text.toString().trim()
        if (inputText.isNotEmpty()) {
            chatViewModel.sendMessage(inputText)
            binding.promptInputEdittext.text?.clear()
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

        // Combine text context and image context for display
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
                        // Find and remove the item from the correct list
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
}