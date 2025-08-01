package com.itsaky.androidide.viewmodel

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.ai.type.FunctionCallPart
import com.itsaky.androidide.data.ChatStorageManager
import com.itsaky.androidide.data.repository.GeminiRepository
import com.itsaky.androidide.models.AgentState
import com.itsaky.androidide.models.ChatMessage
import com.itsaky.androidide.models.ChatSession
import com.itsaky.androidide.models.MessageStatus
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ChatViewModel(
    private val geminiRepository: GeminiRepository
) : ViewModel() {
    private val _sessions = MutableLiveData<MutableList<ChatSession>>(mutableListOf())
    val sessions: LiveData<MutableList<ChatSession>> = _sessions

    private val _currentSession = MutableLiveData<ChatSession?>()
    val currentSession: LiveData<ChatSession?> = _currentSession

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState = _agentState.asStateFlow()

    private val _totalElapsedTime = MutableStateFlow(0L)
    val totalElapsedTime = _totalElapsedTime.asStateFlow()
    private val _stepElapsedTime = MutableStateFlow(0L)
    val stepElapsedTime = _stepElapsedTime.asStateFlow()
    private var timerJob: Job? = null
    private var operationStartTime: Long = 0
    private var stepStartTime: Long = 0


    private var agentJob: Job? = null
    private var saveJob: Job? = null
    private val chatStorageManager: ChatStorageManager

    companion object {
        private const val CURRENT_CHAT_ID_PREF_KEY = "current_chat_id_v1"

        // Set a debounce window (e.g., 500 milliseconds)
        private const val SAVE_DEBOUNCE_MS = 500L
    }

    init {
        val baseDir = IProjectManager.getInstance().projectDir
        val agentDir = File(baseDir, "agent")
        chatStorageManager = ChatStorageManager(agentDir)
        geminiRepository.onToolCall = { functionCall ->
            val toolMessage = formatToolCallForDisplay(functionCall)
            addMessageToCurrentSession(
                ChatMessage(
                    text = toolMessage,
                    sender = ChatMessage.Sender.SYSTEM,
                    status = MessageStatus.SENT
                )
            )
        }
        geminiRepository.onToolMessage = { message ->
            addMessageToCurrentSession(
                ChatMessage(
                    text = "Message from tool: `${message}`",
                    sender = ChatMessage.Sender.SYSTEM,
                    status = MessageStatus.SENT
                )
            )
        }
        geminiRepository.onStateUpdate = { newState ->
            _agentState.value = newState

            // Start, reset, or stop timers based on the new state
            if (newState is AgentState.Processing) {
                if (timerJob?.isActive != true) {
                    // This is the start of a new operation
                    startTimers()
                } else {
                    // This is a new step within the same operation
                    resetStepTimer()
                }
            } else if (newState is AgentState.Idle) {
                stopTimers()
            }
        }
        geminiRepository.onAskUser = { question, options ->
            val formattedMessage = buildString {
                append(question)
                if (options.isNotEmpty()) {
                    append("\n\n**Options:**\n")
                    options.forEach { append("- `$it`\n") }
                }
            }

            addMessageToCurrentSession(
                ChatMessage(
                    text = formattedMessage,
                    sender = ChatMessage.Sender.AGENT,
                    status = MessageStatus.SENT
                )
            )
        }
    }

    private fun formatToolCallForDisplay(functionCall: FunctionCallPart): String {
        val args = functionCall.args.map { (key, value) ->
            "  - **$key**: `${value.toString().removeSurrounding("\"")}`"
        }.joinToString("\n")
        return "\nCalling tool: **`${functionCall.name}`** with arguments:\n$args"
    }

    fun sendMessage(text: String) {
        val fullPrompt = constructFullPrompt(text)
        sendMessage(fullPrompt = fullPrompt, originalUserText = text)
    }

    private fun constructFullPrompt(userInput: String): String {
        val messages = _currentSession.value?.messages ?: return userInput
        if (messages.size < 2) return userInput

        // Find the last message from the AGENT and the last message from the USER before that
        val lastAgentMessage = messages.lastOrNull { it.sender == ChatMessage.Sender.AGENT }
        val lastUserMessageBeforeAgent = messages.lastOrNull {
            it.sender == ChatMessage.Sender.USER && it.timestamp < (lastAgentMessage?.timestamp
                ?: 0)
        }

        // Heuristic: If the last agent message contains "Options:", it was likely a question
        // and the current userInput is the answer.
        if (lastAgentMessage != null && lastUserMessageBeforeAgent != null && lastAgentMessage.text.contains(
                "Options:"
            )
        ) {
            Log.d("ChatViewModel", "Resuming context after user answer.")
            // Re-establish the context for the model
            return """
            The user is responding to your previous question.
            Original user request: "${lastUserMessageBeforeAgent.text}"
            Your question: "${lastAgentMessage.text}"
            User's answer: "$userInput"
            Based on the user's answer, please continue with the original request.
            """.trimIndent()
        }

        // If not resuming, just use the plain user input
        return userInput
    }

    fun sendMessage(fullPrompt: String, originalUserText: String) {
        if (_agentState.value is AgentState.Processing) {
            return
        }

        val userMessage = ChatMessage(text = originalUserText, sender = ChatMessage.Sender.USER)
        addMessageToCurrentSession(userMessage)

        val loadingMessage = ChatMessage(
            text = "...",
            sender = ChatMessage.Sender.AGENT,
            status = MessageStatus.LOADING
        )
        addMessageToCurrentSession(loadingMessage)

        retrieveAgentResponse(fullPrompt, loadingMessage.id, originalUserText)
    }

    private fun retrieveAgentResponse(
        prompt: String,
        messageIdToUpdate: String,
        originalUserPrompt: String
    ) {
        agentJob = viewModelScope.launch {
            try {
                val history = _currentSession.value?.messages?.dropLast(1) ?: emptyList()
                val agentResponse = geminiRepository.generateASimpleResponse(prompt, history)

                updateMessageInCurrentSession(
                    messageId = messageIdToUpdate,
                    newText = agentResponse.text,
                    newStatus = MessageStatus.SENT
                )

                if (agentResponse.report.isNotBlank()) {
                    addMessageToCurrentSession(
                        ChatMessage(
                            text = agentResponse.report,
                            sender = ChatMessage.Sender.SYSTEM,
                            status = MessageStatus.SENT
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    updateMessageInCurrentSession(
                        messageId = messageIdToUpdate,
                        newText = "Operation cancelled by user.",
                        newStatus = MessageStatus.ERROR
                    )

                    // Get the partial report and add it as a new message
                    val partialReport = geminiRepository.getPartialReport()
                    if (partialReport.isNotBlank()) {
                        addMessageToCurrentSession(
                            ChatMessage(
                                text = partialReport,
                                sender = ChatMessage.Sender.SYSTEM,
                                status = MessageStatus.SENT
                            )
                        )
                    }
                } else {
                    updateMessageInCurrentSession(
                        messageId = messageIdToUpdate,
                        newText = "An error occurred. Please try again.",
                        newStatus = MessageStatus.ERROR,
                        originalPrompt = originalUserPrompt
                    )
                }
            } finally {
                _agentState.value = AgentState.Idle
            }
        }
    }

    fun stopAgentResponse() {
        if (agentJob?.isActive == true) {
            agentJob?.cancel()
        }
    }

    fun retryMessage(errorChatMessage: ChatMessage) {
        val originalPrompt =
            errorChatMessage.text // In case of error, we store the original prompt here.

        // Update the message state to LOADING in the UI
        updateMessageInCurrentSession(errorChatMessage.id, "...", MessageStatus.LOADING)

        // Retry the API call
        retrieveAgentResponse(originalPrompt, errorChatMessage.id, originalPrompt)
    }

    private fun addMessageToCurrentSession(message: ChatMessage) {
        val session = _currentSession.value ?: return
        session.messages.add(message)
        _currentSession.postValue(session)
        scheduleSaveCurrentSession()
    }


    private fun updateMessageInCurrentSession(
        messageId: String,
        newText: String,
        newStatus: MessageStatus,
        originalPrompt: String? = null
    ) {
        val session = _currentSession.value ?: return
        val messageIndex = session.messages.indexOfFirst { it.id == messageId }
        if (messageIndex != -1) {
            val text = if (newStatus == MessageStatus.ERROR) originalPrompt ?: newText else newText
            session.messages[messageIndex] = session.messages[messageIndex].copy(
                text = text,
                status = newStatus
            )
            _currentSession.postValue(session)
            scheduleSaveCurrentSession()
        }
    }

    fun loadSessions(prefs: SharedPreferences) {
        val loadedSessions = chatStorageManager.loadAllSessions()

        if (loadedSessions.isEmpty()) {
            // If no chats are found on disk, start with a fresh session
            loadedSessions.add(ChatSession())
        }

        _sessions.value = loadedSessions

        // Find the last active session using the ID from SharedPreferences
        val currentId = prefs.getString(CURRENT_CHAT_ID_PREF_KEY, null)
        _currentSession.value = loadedSessions.find { it.id == currentId } ?: loadedSessions.first()
    }

    fun saveAllSessionsAndState(prefs: SharedPreferences) {
        saveJob?.cancel()
        _currentSession.value?.let { chatStorageManager.saveSession(it) }

        _sessions.value?.let {
            chatStorageManager.saveAllSessions(it)
        }

        _currentSession.value?.let {
            prefs.edit { putString(CURRENT_CHAT_ID_PREF_KEY, it.id) }
        }
    }

    fun createNewSession() {
        val newSession = ChatSession()
        _sessions.value?.add(0, newSession)
        _sessions.postValue(_sessions.value)
        _currentSession.value = newSession
        scheduleSaveCurrentSession()
    }


    fun setCurrentSession(sessionId: String) {
        // Before switching, ensure the current session is saved
        saveJob?.cancel()
        _currentSession.value?.let { chatStorageManager.saveSession(it) }

        val session = _sessions.value?.find { it.id == sessionId }
        if (session != null) {
            _currentSession.value = session
        }
    }

    private fun startTimers() {
        operationStartTime = System.currentTimeMillis()
        stepStartTime = operationStartTime // The first step starts with the operation
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                _totalElapsedTime.value = now - operationStartTime
                _stepElapsedTime.value = now - stepStartTime
                delay(100) // Update UI every 100ms
            }
        }
    }

    private fun resetStepTimer() {
        stepStartTime = System.currentTimeMillis()
        _stepElapsedTime.value = 0L
    }

    private fun stopTimers() {
        timerJob?.cancel()
        _totalElapsedTime.value = 0L
        _stepElapsedTime.value = 0L
    }

    private fun scheduleSaveCurrentSession() {
        // Cancel any previously scheduled save
        saveJob?.cancel()
        // Launch a new coroutine to save after a delay
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            _currentSession.value?.let {
                chatStorageManager.saveSession(it)
            }
        }
    }
}