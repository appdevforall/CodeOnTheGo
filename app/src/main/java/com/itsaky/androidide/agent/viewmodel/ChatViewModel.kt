package com.itsaky.androidide.agent.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.agent.data.ChatStorageManager
import com.itsaky.androidide.agent.repository.AgenticRunner
import com.itsaky.androidide.agent.repository.GeminiRepository
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.ChatSession
import com.itsaky.androidide.agent.MessageStatus
import com.itsaky.androidide.agent.repository.AiBackend
import com.itsaky.androidide.agent.repository.LocalLlmRepositoryImpl
import com.itsaky.androidide.agent.repository.PREF_KEY_AI_BACKEND
import com.itsaky.androidide.agent.repository.PREF_KEY_LOCAL_MODEL_PATH
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChatViewModel : ViewModel() {
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

    // MODIFIED: This is no longer injected, it will be created on-demand.
    private var agentRepository: GeminiRepository? = null
    private var agentJob: Job? = null
    private var saveJob: Job? = null
    private val chatStorageManager: ChatStorageManager

    companion object {
        private const val CURRENT_CHAT_ID_PREF_KEY = "current_chat_id_v1"
        private const val SAVE_DEBOUNCE_MS = 500L
    }

    // NEW METHOD: Creates and configures the correct repository based on SharedPreferences.
    private suspend fun initializeAndGetAgentRepository(context: Context): GeminiRepository? {
        val prefs = BaseApplication.getBaseInstance().prefManager
        val backendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)
        val backend = AiBackend.valueOf(backendName ?: "GEMINI")

        agentRepository = when (backend) {
            AiBackend.GEMINI -> {
                // For Gemini, we create the AgenticRunner.
                // It will internally check for the API key.
                AgenticRunner(context)
            }
            AiBackend.LOCAL_LLM -> {
                val modelPath = prefs.getString(PREF_KEY_LOCAL_MODEL_PATH, null)
                if (modelPath.isNullOrBlank()) {
                    // If no model path is saved, we can't proceed.
                    Log.e("ChatViewModel", "Local LLM backend is selected but no model path is saved.")
                    return null
                }
                // Create the LocalLlmRepositoryImpl instance
                val localRepo = LocalLlmRepositoryImpl(context)
                // IMPORTANT: Load the model. This must complete before any inference.
                if (localRepo.loadModel(modelPath)) {
                    localRepo // Return the successfully initialized repo
                } else {
                    Log.e("ChatViewModel", "Failed to load the local model from path: $modelPath")
                    null // Return null if loading fails
                }
            }
        }
        return agentRepository
    }


    init {
        val baseDir = IProjectManager.getInstance().projectDir
        val agentDir = File(baseDir, "agent")
        chatStorageManager = ChatStorageManager(agentDir)
    }

    fun sendMessage(text: String, context: Context) {
//        val fullPrompt = constructFullPrompt(text)
        sendMessage(fullPrompt = text, originalUserText = text, context)
    }

    private fun constructFullPrompt(userInput: String): String {
        val messages = _currentSession.value?.messages ?: return userInput
        if (messages.size < 2) return userInput
        val lastAgentMessage = messages.lastOrNull { it.sender == ChatMessage.Sender.AGENT }
        val lastUserMessageBeforeAgent = messages.lastOrNull {
            it.sender == ChatMessage.Sender.USER && it.timestamp < (lastAgentMessage?.timestamp
                ?: 0)
        }

        if (lastAgentMessage != null && lastUserMessageBeforeAgent != null && lastAgentMessage.text.contains(
                "Options:"
            )
        ) {
            Log.d("ChatViewModel", "Resuming context after user answer.")
            return """
            The user is responding to your previous question.
            Original user request: "${lastUserMessageBeforeAgent.text}"
            Your question: "${lastAgentMessage.text}"
            User's answer: "$userInput"
            Based on the user's answer, please continue with the original request.
            """.trimIndent()
        }
        return userInput
    }

    fun sendMessage(fullPrompt: String, originalUserText: String, context: Context) {
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
        retrieveAgentResponse(fullPrompt, loadingMessage.id, originalUserText, context)
    }

    // MODIFIED: This function now uses the new on-demand repository creation.
    private fun retrieveAgentResponse(
        prompt: String,
        messageIdToUpdate: String,
        originalUserPrompt: String,
        context: Context
    ) {
        agentJob = viewModelScope.launch {
            try {
                _agentState.value = AgentState.Processing("Initializing AI Backend...")

                // Step 1: Initialize the agent based on current SharedPreferences.
                val repository = initializeAndGetAgentRepository(context)

                // Step 2 (Guard Clause): Check if initialization was successful.
                if (repository == null) {
                    val prefs = BaseApplication.getBaseInstance().prefManager
                    val backendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)
                    val backend = AiBackend.valueOf(backendName ?: "GEMINI")
                    val errorMessage = when (backend) {
                        AiBackend.GEMINI -> "Gemini API Key not found. Please set it in AI Settings."
                        AiBackend.LOCAL_LLM -> "Local LLM model not selected or failed to load. Please select a valid model in AI Settings."
                    }
                    updateMessageInCurrentSession(messageIdToUpdate, errorMessage, MessageStatus.ERROR)
                    return@launch
                }

                _agentState.value = AgentState.Processing("Thinking...")

                // Step 3: Run the agent on a background thread.
                val agentResponse = withContext(Dispatchers.IO) {
                    val history = _currentSession.value?.messages?.dropLast(1) ?: emptyList()
                    repository.generateASimpleResponse(prompt, history)
                }

                // Step 4: Update the UI with the final response.
                updateMessageInCurrentSession(
                    messageId = messageIdToUpdate,
                    newText = agentResponse.text,
                    newStatus = MessageStatus.SENT
                )

                // Step 5: Add the execution report.
                if (agentResponse.report.isNotBlank()) {
                    addMessageToCurrentSession(
                        ChatMessage(text = agentResponse.report, sender = ChatMessage.Sender.SYSTEM)
                    )
                }

            } catch (e: Exception) {
                if (e is CancellationException) {
                    updateMessageInCurrentSession(messageIdToUpdate, "Operation cancelled by user.", MessageStatus.ERROR)
                    val partialReport = agentRepository?.getPartialReport()
                    if (partialReport?.isNotBlank() == true) {
                        addMessageToCurrentSession(
                            ChatMessage(text = partialReport, sender = ChatMessage.Sender.SYSTEM)
                        )
                    }
                } else {
                    updateMessageInCurrentSession(
                        messageId = messageIdToUpdate,
                        newText = "An error occurred: ${e.message}",
                        newStatus = MessageStatus.ERROR,
                        originalPrompt = originalUserPrompt
                    )
                }
            } finally {
                _agentState.value = AgentState.Idle
            }
        }
    }


    // The rest of the file remains the same...

    fun stopAgentResponse() {
        if (agentJob?.isActive == true) {
            agentJob?.cancel()
        }
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
            loadedSessions.add(ChatSession())
        }
        _sessions.value = loadedSessions
        val currentId = prefs.getString(CURRENT_CHAT_ID_PREF_KEY, null)
        _currentSession.value = loadedSessions.find { it.id == currentId } ?: loadedSessions.first()
    }

    fun saveAllSessionsAndState(prefs: SharedPreferences) {
        saveJob?.cancel()
        _currentSession.value?.let { chatStorageManager.saveSession(it) }
        _sessions.value?.let { chatStorageManager.saveAllSessions(it) }
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
        saveJob?.cancel()
        _currentSession.value?.let { chatStorageManager.saveSession(it) }
        val session = _sessions.value?.find { it.id == sessionId }
        if (session != null) {
            _currentSession.value = session
        }
    }

    private fun scheduleSaveCurrentSession() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            _currentSession.value?.let {
                chatStorageManager.saveSession(it)
            }
        }
    }
}