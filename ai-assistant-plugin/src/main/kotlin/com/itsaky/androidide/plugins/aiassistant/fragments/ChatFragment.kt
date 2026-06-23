package com.itsaky.androidide.plugins.aiassistant.fragments

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.plugins.aiassistant.adapters.ChatAdapter
import io.noties.markwon.Markwon
import com.itsaky.androidide.plugins.aiassistant.models.AgentState
import com.itsaky.androidide.plugins.aiassistant.viewmodel.ChatViewModel
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import kotlinx.coroutines.launch

/**
 * ChatFragment for Agent chat UI.
 * Provides a full chat interface with LLM integration.
 */
class ChatFragment : Fragment() {

    private lateinit var viewModel: ChatViewModel
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var clearButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var markwon: Markwon


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FrameLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeMarkwon()
        initializeViewModel()
        buildUI()
        observeViewModel()
    }

    private fun initializeMarkwon() {
        markwon = Markwon.create(requireContext())
    }

    override fun onResume() {
        super.onResume()
        // Check backend availability when fragment becomes visible
        // This ensures we check after all plugins have loaded
        viewModel.checkBackendAvailability()
    }

    private fun initializeViewModel() {
        // Pass plugin context getter instead of service directly
        // This allows ViewModel to get service lazily
        viewModel = ViewModelProvider(
            this,
            ChatViewModelFactory { getPluginContext() }
        )[ChatViewModel::class.java]
    }

    private fun getPluginContext(): PluginContext? {
        // Access the plugin context via the companion object
        return com.itsaky.androidide.plugins.aiassistant.AiAssistantPlugin.getContext()
    }

    private fun buildUI() {
        val rootView = view as? ViewGroup ?: return

        val mainContainer = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        // Toolbar with settings button
        val toolbar = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }

        val toolbarTitle = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = "AI Chat"
            textSize = 18f
            setPadding(8, 8, 8, 8)
        }
        toolbar.addView(toolbarTitle)

        settingsButton = ImageButton(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Use Android's built-in more_vert icon (3 dots)
            setImageResource(android.R.drawable.ic_menu_more)
            setOnClickListener { showSettingsMenu(it) }
            background = null // Remove default button background
            setPadding(16, 16, 16, 16)
        }
        toolbar.addView(settingsButton)

        mainContainer.addView(toolbar)

        // Status bar
        statusTextView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "Ready"
            textSize = 14f
            setPadding(8, 8, 8, 8)
        }
        mainContainer.addView(statusTextView)

        // Progress bar
        progressBar = ProgressBar(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        mainContainer.addView(progressBar)

        // Clear button
        clearButton = Button(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "Clear Chat"
            setOnClickListener { onClearClicked() }
            setPadding(8, 8, 8, 8)
        }
        mainContainer.addView(clearButton)

        // Messages RecyclerView
        // Use plugin context for adapter to ensure proper resource inflation
        val pluginContext = getPluginContext()?.androidContext ?: requireContext()
        chatAdapter = ChatAdapter(pluginContext, markwon) { action, message ->
            onMessageAction(action, message)
        }
        messagesRecyclerView = RecyclerView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
        mainContainer.addView(messagesRecyclerView)

        // Input container
        val inputContainer = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }

        inputEditText = EditText(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            hint = "Enter your message..."
            setPadding(8, 8, 8, 8)
        }
        inputContainer.addView(inputEditText)

        sendButton = Button(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "Send"
            isEnabled = false
            setOnClickListener { onSendClicked() }
        }
        inputContainer.addView(sendButton)

        mainContainer.addView(inputContainer)
        rootView.addView(mainContainer)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.messages.collect { messages ->
                chatAdapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    messagesRecyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.agentState.collect { state ->
                updateStatusUI(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isBackendAvailable.collect { isAvailable ->
                sendButton.isEnabled = isAvailable && viewModel.agentState.value is AgentState.Idle
                if (!isAvailable) {
                    statusTextView.text = "No LLM backend available. Configure AI Core plugin."
                } else if (statusTextView.text == "No LLM backend available. Configure AI Core plugin.") {
                    statusTextView.text = "Ready"
                }
            }
        }
    }

    private fun updateStatusUI(state: AgentState) {
        when (state) {
            is AgentState.Idle -> {
                statusTextView.text = "Ready"
                progressBar.visibility = View.GONE
                sendButton.isEnabled = viewModel.isBackendAvailable.value
            }
            is AgentState.Processing -> {
                statusTextView.text = "Processing: ${state.message}"
                progressBar.visibility = View.VISIBLE
                sendButton.isEnabled = false
            }
            is AgentState.Cancelling -> {
                statusTextView.text = "Cancelling..."
                progressBar.visibility = View.VISIBLE
                sendButton.isEnabled = false
            }
            is AgentState.Error -> {
                statusTextView.text = "Error: ${state.message}"
                progressBar.visibility = View.GONE
                sendButton.isEnabled = viewModel.isBackendAvailable.value
                Toast.makeText(requireContext(), "Error: ${state.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onSendClicked() {
        val message = inputEditText.text.toString().trim()
        if (message.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter a message", Toast.LENGTH_SHORT).show()
            return
        }
        inputEditText.text.clear()
        viewModel.sendMessage(message)
    }

    private fun onClearClicked() {
        viewModel.clearMessages()
        Toast.makeText(requireContext(), "Chat cleared", Toast.LENGTH_SHORT).show()
    }

    private fun onMessageAction(action: String, message: com.itsaky.androidide.plugins.aiassistant.models.ChatMessage) {
        when (action) {
            ChatAdapter.ACTION_EDIT -> {
                // Show dialog to edit message
                inputEditText.setText(message.text)
                inputEditText.requestFocus()
            }
            ChatAdapter.ACTION_RETRY -> {
                // Resend the message
                viewModel.sendMessage(message.text)
            }
            ChatAdapter.ACTION_OPEN_SETTINGS -> {
                // Open settings fragment
                openSettingsFragment()
            }
        }
    }

    private fun showSettingsMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Settings")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    openSettingsFragment()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openSettingsFragment() {
        val settingsFragment = AiSettingsFragment()
        // Show as dialog fragment to avoid overlapping with chat UI
        settingsFragment.show(parentFragmentManager, "ai_settings")
    }


    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopProcessing()
    }
}

/**
 * Factory for creating ChatViewModel with PluginContext dependency.
 */
class ChatViewModelFactory(
    private val getContext: () -> PluginContext?
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(getContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
