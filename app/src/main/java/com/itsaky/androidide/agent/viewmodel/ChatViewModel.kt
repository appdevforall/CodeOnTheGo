package com.itsaky.androidide.agent.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.ChatSession
import com.itsaky.androidide.agent.MessageStatus
import com.itsaky.androidide.agent.data.ChatStorageManager
import com.itsaky.androidide.agent.repository.AgenticRunner
import com.itsaky.androidide.agent.repository.AiBackend
import com.itsaky.androidide.agent.repository.GeminiRepository
import com.itsaky.androidide.agent.repository.LocalLlmRepositoryImpl
import com.itsaky.androidide.agent.repository.PREF_KEY_AI_BACKEND
import com.itsaky.androidide.agent.repository.PREF_KEY_LOCAL_MODEL_PATH
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.utils.getFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class BackendStatus(val displayText: String)

class ChatViewModel : ViewModel() {
    private val _sessions = MutableLiveData<MutableList<ChatSession>>(mutableListOf())
    val sessions: LiveData<MutableList<ChatSession>> = _sessions

    private val _currentSession = MutableLiveData<ChatSession?>()
    val currentSession: LiveData<ChatSession?> = _currentSession

    private val _backendStatus = MutableLiveData<BackendStatus>()
    val backendStatus: LiveData<BackendStatus> = _backendStatus

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState = _agentState.asStateFlow()

    private val _totalElapsedTime = MutableStateFlow(0L)
    val totalElapsedTime = _totalElapsedTime.asStateFlow()
    private val _stepElapsedTime = MutableStateFlow(0L)
    val stepElapsedTime = _stepElapsedTime.asStateFlow()
    private var timerJob: Job? = null
    private var operationStartTime: Long = 0
    private var stepStartTime: Long = 0

    private var agentRepository: GeminiRepository? = null
    private var agentJob: Job? = null
    private var saveJob: Job? = null
    private val chatStorageManager: ChatStorageManager

    private var lastKnownBackendName: String? = null
    private var lastKnownModelPath: String? = null

    companion object {
        private const val CURRENT_CHAT_ID_PREF_KEY = "current_chat_id_v1"
        private const val SAVE_DEBOUNCE_MS = 500L
    }

    fun addSystemMessage(text: String) {
        val systemMessage = ChatMessage(
            text = text,
            sender = ChatMessage.Sender.SYSTEM
        )
        addMessageToCurrentSession(systemMessage)
    }

    fun checkBackendStatusOnResume(context: Context) {
        val prefs = BaseApplication.getBaseInstance().prefManager
        val currentBackendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)!!
        val currentModelPath = prefs.getString(PREF_KEY_LOCAL_MODEL_PATH, null)
        val backend = AiBackend.valueOf(currentBackendName)

        val displayText = buildBackendDisplayText(backend, currentModelPath, context)
        _backendStatus.value = BackendStatus(displayText)

        val isFirstCheck = lastKnownBackendName == null
        val backendChanged = !isFirstCheck && lastKnownBackendName != currentBackendName
        val modelChanged =
            !isFirstCheck && lastKnownBackendName == AiBackend.LOCAL_LLM.name && lastKnownModelPath != currentModelPath

        if (backendChanged || modelChanged) {
            val message = buildSystemMessage(backend, currentModelPath, context)
            addSystemMessage(message)
        }

        lastKnownBackendName = currentBackendName
        lastKnownModelPath = currentModelPath
    }

    private fun buildSystemMessage(
        backend: AiBackend,
        modelPath: String?,
        context: Context
    ): String {
        val backendDisplayName = backend.name.replace("_", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        val message = StringBuilder("🤖 System: $backendDisplayName backend selected.")
        if (backend == AiBackend.LOCAL_LLM) {
            if (modelPath != null) {
                val fileName = modelPath.toUri().getFileName(context)
                message.append("\nCurrent model: $fileName")
            } else {
                message.append("\n⚠️ Warning: No model file selected.")
            }
        }
        return message.toString()
    }

    private suspend fun initializeAndGetAgentRepository(context: Context): GeminiRepository? {
        val prefs = BaseApplication.getBaseInstance().prefManager
        val backendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)
        val backend = AiBackend.valueOf(backendName ?: "GEMINI")

        agentRepository = when (backend) {
            AiBackend.GEMINI -> {
                AgenticRunner(context).apply {
                    onProgressUpdate =
                        { progressMessage -> addMessageToCurrentSession(progressMessage) }
                    onStateUpdate = { newState ->
                        _agentState.value = newState
                        if (newState is AgentState.Processing) {
                            resetStepTimer()
                        }
                    }
                }
            }

            AiBackend.LOCAL_LLM -> {
                val modelPath = prefs.getString(PREF_KEY_LOCAL_MODEL_PATH, null)
                if (modelPath.isNullOrBlank()) {
                    Log.e(
                        "ChatViewModel",
                        "Local LLM backend is selected but no model path is saved."
                    )
                    return null
                }
                val localRepo = LocalLlmRepositoryImpl(context).apply {
                    onProgressUpdate =
                        { progressMessage -> addMessageToCurrentSession(progressMessage) }
                    onStateUpdate = { newState ->
                        _agentState.value = newState
                        if (newState is AgentState.Processing) {
                            resetStepTimer()
                        }
                    }
                }
                if (localRepo.loadModel(modelPath)) {
                    localRepo
                } else {
                    Log.e("ChatViewModel", "Failed to load the local model from path: $modelPath")
                    null
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

    fun formatTime(millis: Long): String {
        if (millis < 0) return ""

        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(minutes)
        val remainingMillis = millis % 1000

        val totalSeconds = seconds + (remainingMillis / 1000.0)

        return if (minutes > 0) {
            String.format(Locale.US, "%dm %.1fs", minutes, totalSeconds)
        } else {
            String.format(Locale.US, "%.1fs", totalSeconds)
        }
    }

    private fun retrieveAgentResponse(
        prompt: String,
        messageIdToUpdate: String,
        originalUserPrompt: String,
        context: Context
    ) {
        agentJob = viewModelScope.launch {
            startTimer()
            try {
                _agentState.value = AgentState.Processing("Initializing AI Backend...")
                resetStepTimer()

                val repository = initializeAndGetAgentRepository(context)
                if (repository == null) {
                    val prefs = BaseApplication.getBaseInstance().prefManager
                    val backendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)
                    val backend = AiBackend.valueOf(backendName ?: "GEMINI")
                    val errorMessage = when (backend) {
                        AiBackend.GEMINI -> "Gemini API Key not found. Please set it in AI Settings."
                        AiBackend.LOCAL_LLM -> "Local LLM model not selected or failed to load. Please select a valid model in AI Settings."
                    }
                    updateMessageInCurrentSession(
                        messageIdToUpdate,
                        errorMessage,
                        MessageStatus.ERROR
                    )
                    return@launch
                }

                val agentResponse = withContext(Dispatchers.IO) {
                    val history = _currentSession.value?.messages?.dropLast(1) ?: emptyList()
                    repository.generateASimpleResponse(prompt, history)
                }

                removeMessageFromCurrentSession(messageIdToUpdate)

                addMessageToCurrentSession(
                    ChatMessage(
                        text = agentResponse.text,
                        sender = ChatMessage.Sender.AGENT,
                        status = MessageStatus.SENT
                    )
                )

                if (agentResponse.report.isNotBlank()) {
                    addMessageToCurrentSession(
                        ChatMessage(text = agentResponse.report, sender = ChatMessage.Sender.SYSTEM)
                    )
                }

            } catch (e: Exception) {
                if (e is CancellationException) {
                    updateMessageInCurrentSession(
                        messageIdToUpdate,
                        "Operation cancelled by user.",
                        MessageStatus.ERROR
                    )
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
                val finalTimeMillis = _totalElapsedTime.value
                if (finalTimeMillis > 100) {
                    val formattedTime = formatTime(finalTimeMillis)
                    addSystemMessage("🤖 Workflow finished in $formattedTime.")
                }

                _agentState.value = AgentState.Idle
                stopTimer()
            }
        }
    }

    private fun buildBackendDisplayText(
        backend: AiBackend,
        modelPath: String?,
        context: Context
    ): String {
        return when (backend) {
            AiBackend.GEMINI -> "Gemini"
            AiBackend.LOCAL_LLM -> {
                if (modelPath != null) {
                    val fileName = modelPath.toUri().getFileName(context)
                    if (fileName.length > 15) "${fileName.take(12)}..." else fileName
                } else {
                    "Local LLM"
                }
            }
        }
    }

    fun stopAgentResponse() {
        agentRepository?.stop()
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

    private fun removeMessageFromCurrentSession(messageId: String) {
        val session = _currentSession.value ?: return
        val currentMessages = session.messages.toMutableList()
        val messageIndex = currentMessages.indexOfFirst { it.id == messageId }
        if (messageIndex != -1) {
            currentMessages.removeAt(messageIndex)
            session.messages.clear()
            session.messages.addAll(currentMessages)
            _currentSession.postValue(session)
        }
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

    /**
     * Starts a background coroutine to update the elapsed time flows every 100ms.
     */
    private fun startTimer() {
        stopTimer() // Ensure any previous timer is stopped
        operationStartTime = System.currentTimeMillis()
        stepStartTime = System.currentTimeMillis()
        timerJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                _totalElapsedTime.value = now - operationStartTime
                _stepElapsedTime.value = now - stepStartTime
                delay(100) // Update frequency
            }
        }
    }

    /**
     * Stops the timer coroutine and resets the elapsed time values.
     */
    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _totalElapsedTime.value = 0L
        _stepElapsedTime.value = 0L
    }

    /**
     * Resets the step timer when the agent moves to a new task (e.g., thinking -> acting).
     */
    private fun resetStepTimer() {
        stepStartTime = System.currentTimeMillis()
        // Reset to 0 immediately for a responsive UI
        _stepElapsedTime.value = 0L
    }
}