package com.itsaky.androidide.viewmodel

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.ai.type.FunctionCallPart
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.data.repository.AgentResponse
import com.itsaky.androidide.data.repository.GeminiRepository
import com.itsaky.androidide.models.AgentState
import com.itsaky.androidide.models.ChatMessage
import com.itsaky.androidide.models.ChatSession
import com.itsaky.androidide.models.MessageStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val geminiRepository: GeminiRepository
) : ViewModel() {
    private val _sessions = MutableLiveData<MutableList<ChatSession>>(mutableListOf())
    val sessions: LiveData<MutableList<ChatSession>> = _sessions

    private val _currentSession = MutableLiveData<ChatSession?>()
    val currentSession: LiveData<ChatSession?> = _currentSession

    private val gson = Gson()

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState = _agentState.asStateFlow()

    private var agentJob: Job? = null

    companion object {
        private const val CHAT_HISTORY_LIST_PREF_KEY = "chat_history_list_v1"
        private const val CURRENT_CHAT_ID_PREF_KEY = "current_chat_id_v1"
    }

    init {
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

    fun sendMessage(text: String) {
        sendMessage(fullPrompt = text, originalUserText = text)
    }

    private fun retrieveAgentResponse(
        prompt: String,
        messageIdToUpdate: String,
        originalUserPrompt: String
    ) {
        agentJob = viewModelScope.launch {
            try {
                // Agent state is now handled by the repository callback

                val history = _currentSession.value?.messages?.toList() ?: emptyList()
                val agentResponse: AgentResponse =
                    geminiRepository.generateASimpleResponse(prompt, history)

                // Update the original loading message with the main text
                updateMessageInCurrentSession(
                    messageId = messageIdToUpdate,
                    newText = agentResponse.text,
                    newStatus = MessageStatus.SENT
                )

                // If there's a report, add it as a new system message
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
                        newText = "Operation cancelled.",
                        newStatus = MessageStatus.ERROR
                    )
                } else {
                    updateMessageInCurrentSession(
                        messageId = messageIdToUpdate,
                        newText = "An error occurred. Please try again.",
                        newStatus = MessageStatus.ERROR,
                        originalPrompt = originalUserPrompt
                    )
                }
            } finally {
                // When the entire operation is finished (or fails), reset the state to Idle.
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
        }
    }

    fun loadSessions(prefs: SharedPreferences) {
        val json = prefs.getString(CHAT_HISTORY_LIST_PREF_KEY, null)
        val loadedSessions: MutableList<ChatSession> = if (json != null) {
            val type = object : TypeToken<MutableList<ChatSession>>() {}.type
            gson.fromJson(json, type)
        } else {
            mutableListOf()
        }

        if (loadedSessions.isEmpty()) {
            loadedSessions.add(ChatSession())
        }

        _sessions.value = loadedSessions

        val currentId = prefs.getString(CURRENT_CHAT_ID_PREF_KEY, null)
        _currentSession.value = loadedSessions.find { it.id == currentId } ?: loadedSessions.first()
    }

    fun saveSessions(prefs: SharedPreferences) {
        val json = gson.toJson(_sessions.value)
        prefs.edit { putString(CHAT_HISTORY_LIST_PREF_KEY, json) }

        _currentSession.value?.let {
            prefs.edit { putString(CURRENT_CHAT_ID_PREF_KEY, it.id) }
        }
    }

    fun createNewSession() {
        val newSession = ChatSession()
        _sessions.value?.add(0, newSession)
        _currentSession.value = newSession
    }

    fun setCurrentSession(sessionId: String) {
        val session = _sessions.value?.find { it.id == sessionId }
        if (session != null) {
            _currentSession.value = session
        }
    }
}