package com.itsaky.androidide.agent.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ApprovalId
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.ChatSession
import com.itsaky.androidide.agent.MessageStatus
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.data.ChatStorageManager
import com.itsaky.androidide.agent.events.ExecCommandBegin
import com.itsaky.androidide.agent.events.ExecCommandEnd
import com.itsaky.androidide.agent.model.ReviewDecision
import com.itsaky.androidide.agent.repository.AiBackend
import com.itsaky.androidide.agent.repository.DEFAULT_GEMINI_MODEL
import com.itsaky.androidide.agent.repository.GeminiAgenticRunner
import com.itsaky.androidide.agent.repository.GeminiRepository
import com.itsaky.androidide.agent.repository.LlmInferenceEngineProvider
import com.itsaky.androidide.agent.repository.LocalAgenticRunner
import com.itsaky.androidide.agent.repository.PREF_KEY_AI_BACKEND
import com.itsaky.androidide.agent.repository.PREF_KEY_GEMINI_MODEL
import com.itsaky.androidide.agent.repository.PREF_KEY_LOCAL_MODEL_PATH
import com.itsaky.androidide.agent.repository.SessionHistoryRepository
import com.itsaky.androidide.agent.tool.shell.ParsedCommand
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.utils.getFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class BackendStatus(val displayText: String)

class ChatViewModel : ViewModel() {
    private val log = LoggerFactory.getLogger(ChatViewModel::class.java)

    // --- State Exposure ---
    private val _sessions = MutableLiveData<MutableList<ChatSession>>(mutableListOf())
    val sessions: LiveData<MutableList<ChatSession>> = _sessions
    private val _currentSession = MutableLiveData<ChatSession?>()
    val currentSession: LiveData<ChatSession?> = _currentSession

    // A flow that holds the current, active repository instance
    private val _repository = MutableStateFlow<GeminiRepository?>(null)
    private val _commandMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    // The public chatMessages flow now switches its subscription to the latest repository's flow.
    // This is the single source of truth for the UI.
    @OptIn(ExperimentalCoroutinesApi::class)
    val chatMessages: StateFlow<List<ChatMessage>> = _repository.flatMapLatest { repo ->
        val baseMessages = repo?.messages ?: flowOf(emptyList())
        baseMessages.combine(_commandMessages) { base, commands ->
            base + commands
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    private val _backendStatus = MutableLiveData<BackendStatus>()
    val backendStatus: LiveData<BackendStatus> = _backendStatus
    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState = _agentState.asStateFlow()
    private val _totalElapsedTime = MutableStateFlow(0L)
    val totalElapsedTime = _totalElapsedTime.asStateFlow()
    private val _stepElapsedTime = MutableStateFlow(0L)
    val stepElapsedTime = _stepElapsedTime.asStateFlow()

    // --- Private Properties ---
    private var agentRepository: GeminiRepository?
        get() = _repository.value
        set(value) {
            _repository.value = value
        }
    private var agentJob: Job? = null
    private var saveJob: Job? = null
    private var timerJob: Job? = null
    private var repoMessagesJob: Job? = null
    private var repoExecEventsJob: Job? = null
    private var operationStartTime: Long = 0
    private var stepStartTime: Long = 0
    private val chatStorageManager: ChatStorageManager
    private var lastKnownBackendName: String? = null
    private var lastKnownModelPath: String? = null
    private var lastKnownGeminiModel: String? = null
    private var lastAutoLoadFailurePath: String? = null

    companion object {
        private const val CURRENT_CHAT_ID_PREF_KEY = "current_chat_id_v1"
        private const val SAVE_DEBOUNCE_MS = 500L
    }

    init {
        val storageRoot = File(BaseApplication.getBaseInstance().filesDir, "chat_sessions")
        val agentDir = File(storageRoot, determineProjectBucket())
        chatStorageManager = ChatStorageManager(agentDir)
    }


    private fun getOrCreateRepository(context: Context): GeminiRepository? {
        val prefs = BaseApplication.getBaseInstance().prefManager
        val backendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)
        val modelPath = prefs.getString(PREF_KEY_LOCAL_MODEL_PATH, null)
        val geminiModel = prefs.getString(PREF_KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL)

        val shouldReuseExisting = agentRepository != null &&
                agentRepository !is SessionHistoryRepository &&
            lastKnownBackendName == backendName &&
            lastKnownModelPath == modelPath &&
            lastKnownGeminiModel == geminiModel
        // If the repository exists and settings haven't changed, return the existing instance.
        if (shouldReuseExisting) {
            return agentRepository
        }

        agentRepository?.destroy()
        agentRepository = null
        observeRepositoryMessages(null)
        _commandMessages.value = emptyList()

        log.info("Settings changed or repository not initialized. Creating new instance.")
        lastKnownBackendName = backendName
        lastKnownModelPath = modelPath
        lastKnownGeminiModel = geminiModel
        val backend = AiBackend.valueOf(backendName ?: "GEMINI")

        agentRepository = when (backend) {
            AiBackend.GEMINI -> {
                log.info("Creating new GeminiAgenticRunner instance.")
                GeminiAgenticRunner(
                    context,
                    plannerModel = geminiModel ?: DEFAULT_GEMINI_MODEL
                ).apply {
                    onStateUpdate = { _agentState.value = it }
                }
            }

            AiBackend.LOCAL_LLM -> {
                // Get the SINGLE, SHARED instance of the engine
                val engine = LlmInferenceEngineProvider.instance

                // The model should ALREADY be loaded by the settings page.
                // We just check if it's ready.
                if (!engine.isModelLoaded) {
                    log.error("Initialization failed: Local LLM model is not loaded.")
                    null // Return null to show an error message in the UI
                } else {
                    log.info("Creating LocalAgenticRunner instance.")
                    LocalAgenticRunner(context, engine).apply {
                        onStateUpdate = { _agentState.value = it }
                    }
                }
            }
        }
        val repo = agentRepository
        val currentHistory = _currentSession.value?.messages
        if (repo != null && currentHistory != null) {
            repo.loadHistory(currentHistory)
        }

        observeRepositoryMessages(repo)
        return agentRepository
    }

    fun checkBackendStatusOnResume(context: Context) {
        val prefs = BaseApplication.getBaseInstance().prefManager
        val currentBackendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)!!
        val currentModelPath = prefs.getString(PREF_KEY_LOCAL_MODEL_PATH, null)
        val currentGeminiModel = prefs.getString(PREF_KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL)
        val backend = AiBackend.valueOf(currentBackendName)

        val configChanged = currentBackendName != lastKnownBackendName ||
            currentModelPath != lastKnownModelPath ||
            currentGeminiModel != lastKnownGeminiModel
        if (configChanged) {
            agentRepository?.stop()
            agentRepository = null
            observeRepositoryMessages(null)
            _commandMessages.value = emptyList()
            ensureHistoryVisible(_currentSession.value?.messages ?: emptyList())
        }

        if (backend == AiBackend.LOCAL_LLM) {
            viewModelScope.launch {
                autoLoadLocalModelIfNeeded(context)
            }
        } else {
            lastAutoLoadFailurePath = null
        }

        val displayText = buildBackendDisplayText(
            backend = backend,
            modelPath = currentModelPath,
            geminiModel = currentGeminiModel,
            context = context
        )
        _backendStatus.value = BackendStatus(displayText)

        lastKnownBackendName = currentBackendName
        lastKnownModelPath = currentModelPath
        lastKnownGeminiModel = currentGeminiModel
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

    fun sendMessage(fullPrompt: String, originalUserText: String, context: Context) {
        val currentState = _agentState.value
        if (isAgentBusy(currentState)) {
            log.warn("sendMessage called while agent was busy. Ignoring.")
            return
        }

        if (currentState is AgentState.Error) {
            _agentState.value = AgentState.Idle
        }

        _agentState.value = AgentState.Initializing("Preparing agent...")

        retrieveAgentResponse(fullPrompt, originalUserText, context)
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
        originalUserPrompt: String,
        context: Context
    ) {
        agentJob = viewModelScope.launch {
            startTimer()
            log.info("Starting agent workflow for prompt: \"{}\"", originalUserPrompt)
            try {
                autoLoadLocalModelIfNeeded(context)
                // Now we just get the stable repository instance.
                val repository = getOrCreateRepository(context)
                if (repository == null) {
                    log.error("Aborting workflow: AI repository failed to initialize.")
                    val backendName = lastKnownBackendName
                    if (backendName == AiBackend.LOCAL_LLM.name) {
                        postSystemError(
                            "Local model is not loaded. Open AI Settings, pick a model, and try again."
                        )
                    } else {
                        postSystemError("The AI backend is not ready. Please review your AI settings.")
                    }
                    return@launch
                }

                resetStepTimer()

                withContext(Dispatchers.IO) {
                    val history = _currentSession.value?.messages?.dropLast(1) ?: emptyList()
                    log.debug(
                        "--- AGENT REQUEST ---\nPrompt: {}\nHistory Messages: {}",
                        prompt,
                        history.size
                    )
                    repository.generateASimpleResponse(prompt, history)
                }


                log.info("Displaying final agent response to user.")

            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> {
                        log.warn("Workflow was cancelled by user.")
                    }

                    else -> {
                        log.error("An unexpected error occurred during agent workflow.", e)
                        postSystemError(e.message ?: "Unexpected error during agent workflow.")
                    }
                }
            } finally {
                val finalTimeMillis = _totalElapsedTime.value
                if (finalTimeMillis > 100) {
                    val formattedTime = formatTime(finalTimeMillis)
                    log.info("Workflow finished in {}.", formattedTime)
                } else {
                    log.info("Workflow finished.")
                }
                if (_agentState.value !is AgentState.Error) {
                    _agentState.value = AgentState.Idle
                }
                stopTimer()
            }
        }
    }

    private fun buildBackendDisplayText(
        backend: AiBackend,
        modelPath: String?,
        geminiModel: String?,
        context: Context
    ): String {
        return when (backend) {
            AiBackend.GEMINI -> {
                val modelName = geminiModel ?: DEFAULT_GEMINI_MODEL
                "Gemini ($modelName)"
            }
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
        _agentState.value = AgentState.Thinking("Stopping...")
        agentRepository?.stop()
        if (agentJob?.isActive == true) {
            agentJob?.cancel()
        }
    }
    // --- Session Management ---

    fun loadSessions(prefs: SharedPreferences) {
        val loadedSessions = chatStorageManager.loadAllSessions()
        if (loadedSessions.isEmpty()) {
            loadedSessions.add(ChatSession())
        }
        _sessions.value = loadedSessions
        val currentId = prefs.getString(CURRENT_CHAT_ID_PREF_KEY, null)
        val session = loadedSessions.find { it.id == currentId } ?: loadedSessions.first()
        _currentSession.value = session
        ensureHistoryVisible(session.messages)
    }

    private suspend fun autoLoadLocalModelIfNeeded(context: Context) {
        val prefs = BaseApplication.getBaseInstance().prefManager
        val backendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)
        val backend = AiBackend.valueOf(backendName ?: AiBackend.GEMINI.name)
        if (backend != AiBackend.LOCAL_LLM) {
            return
        }

        val engine = LlmInferenceEngineProvider.instance
        if (engine.isModelLoaded) {
            return
        }

        val modelPath = prefs.getString(PREF_KEY_LOCAL_MODEL_PATH, null) ?: return
        val modelUri = modelPath.toUri()
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == modelUri && it.isReadPermission
        }

        if (!hasPermission) {
            log.warn("Skipping auto-load. Missing persisted permission for {}", modelPath)
            if (lastAutoLoadFailurePath != modelPath) {
                postSystemMessage(
                    "⚠️ System: Unable to auto-load the saved local model. Select it again in AI Settings."
                )
                lastAutoLoadFailurePath = modelPath
            }
            return
        }

        log.info("Attempting to auto-load saved local model from {}", modelPath)
        val success = engine.initModelFromFile(context, modelPath)
        if (success) {
            val displayName = engine.loadedModelName ?: modelUri.getFileName(context)
            log.info("Auto-load succeeded for local model {}", displayName)
            lastAutoLoadFailurePath = null
            postSystemMessage(
                "🤖 System: Loaded saved local model \"$displayName\" automatically."
            )
        } else {
            log.error("Auto-load failed for local model {}", modelPath)
            if (lastAutoLoadFailurePath != modelPath) {
                postSystemMessage(
                    "⚠️ System: Failed to auto-load the saved local model. Select it again in AI Settings."
                )
                lastAutoLoadFailurePath = modelPath
            }
        }
    }

    private fun postSystemMessage(message: String) {
        val session = _currentSession.value ?: return
        val systemMessage = ChatMessage(
            text = message,
            sender = Sender.SYSTEM,
            status = MessageStatus.SENT
        )
        session.messages.add(systemMessage)
        _sessions.value = _sessions.value
        ensureHistoryVisible(session.messages)
        scheduleSaveCurrentSession()
    }

    private fun postSystemError(message: String) {
        val session = _currentSession.value ?: return
        val errorMessage = ChatMessage(
            text = message,
            sender = Sender.SYSTEM,
            status = MessageStatus.ERROR
        )
        session.messages.add(errorMessage)
        _sessions.value = _sessions.value
        ensureHistoryVisible(session.messages)
        scheduleSaveCurrentSession()
        _agentState.value = AgentState.Error(message)
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
        agentRepository?.stop()
        agentRepository = null
        lastKnownBackendName = null
        lastKnownModelPath = null
        lastKnownGeminiModel = null
        observeRepositoryMessages(null)
        ensureHistoryVisible(newSession.messages)
        scheduleSaveCurrentSession()
    }

    fun setCurrentSession(sessionId: String) {
        saveJob?.cancel()
        _currentSession.value?.let { chatStorageManager.saveSession(it) }
        val session = _sessions.value?.find { it.id == sessionId }
        if (session != null) {
            _currentSession.value = session
            ensureHistoryVisible(session.messages)
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

    private fun observeRepositoryMessages(repo: GeminiRepository?) {
        repoMessagesJob?.cancel()
        repoExecEventsJob?.cancel()
        if (repo == null) {
            return
        }
        repoMessagesJob = viewModelScope.launch {
            repo.messages.collect { messages ->
                val session = _currentSession.value ?: return@collect
                session.messages.apply {
                    clear()
                    addAll(messages)
                }
                _currentSession.postValue(session)
                _sessions.postValue(_sessions.value)
                scheduleSaveCurrentSession()
            }
        }
        repoExecEventsJob = viewModelScope.launch {
            repo.execEvents.collect { event ->
                when (event) {
                    is ExecCommandBegin -> handleCommandBegin(event)
                    is ExecCommandEnd -> handleCommandEnd(event)
                }
            }
        }
    }

    private fun handleCommandBegin(event: ExecCommandBegin) {
        val message = ChatMessage(
            id = event.callId,
            text = formatRunningCommand(event),
            sender = Sender.TOOL,
            status = MessageStatus.LOADING,
            timestamp = System.currentTimeMillis()
        )
        _commandMessages.update { current -> current + message }
    }

    private fun handleCommandEnd(event: ExecCommandEnd) {
        _commandMessages.update { current ->
            val index = current.indexOfFirst { it.id == event.callId }
            val updated = buildCompletedMessage(event, current.getOrNull(index))
            val mutable = current.toMutableList()
            if (index == -1) {
                mutable += updated
            } else {
                mutable[index] = updated
            }
            mutable
        }
    }

    private fun buildCompletedMessage(
        event: ExecCommandEnd,
        existing: ChatMessage?
    ): ChatMessage {
        val base = existing ?: ChatMessage(
            id = event.callId,
            text = "",
            sender = Sender.TOOL,
            timestamp = System.currentTimeMillis()
        )
        val duration = event.durationMillis.takeIf { it > 0 }
        return base.copy(
            text = formatCompletedCommand(event),
            status = MessageStatus.SENT,
            durationMs = duration
        )
    }

    private fun formatRunningCommand(event: ExecCommandBegin): String {
        val displayCommand = if (event.command.isBlank()) {
            "shell command"
        } else {
            event.command
        }
        return "**Running...** `" + displayCommand + "`"
    }

    private fun formatCompletedCommand(event: ExecCommandEnd): String {
        val commandLine = if (event.command.isBlank()) {
            "shell command"
        } else {
            event.command
        }
        val highlightedCommand = "`$commandLine`"
        return if (event.success) {
            if (event.parsedCommand.isExploration) {
                "**Ran.** $highlightedCommand\n${formatExplorationSummary(event.parsedCommand)}"
            } else {
                val output = event.formattedOutput.ifBlank { "Command completed with no output." }
                buildString {
                    append("**Ran.** $highlightedCommand\n")
                    append("```text\n")
                    append(output)
                    append("\n```")
                    if (event.truncated) {
                        append("\n_Output truncated._")
                    }
                }
            }
        } else {
            val failureText = (event.sandboxFailureMessage ?: event.formattedOutput)
                .ifBlank { "Command failed." }
            buildString {
                append("**Failed.** $highlightedCommand\n")
                append("```text\n")
                append(failureText)
                append("\n```")
            }
        }
    }

    private fun formatExplorationSummary(parsedCommand: ParsedCommand): String {
        return when (parsedCommand) {
            is ParsedCommand.Read -> {
                val total = parsedCommand.files.size
                val label = if (total == 1) "file" else "files"
                val sample = parsedCommand.files.take(3).joinToString(", ")
                val suffix = if (sample.isNotEmpty()) ": $sample" else ""
                "• Exploring: Read $total $label$suffix"
            }

            is ParsedCommand.ListFiles -> {
                "• Exploring: List ${parsedCommand.path}"
            }

            is ParsedCommand.Search -> {
                val pathHint = parsedCommand.path?.let { " in $it" } ?: ""
                "• Exploring: Search \"${parsedCommand.query}\"$pathHint"
            }

            is ParsedCommand.Unknown -> "• Exploring command"
        }
    }

    private fun ensureHistoryVisible(messages: List<ChatMessage>) {
        when (val repo = agentRepository) {
            null -> {
                val replayRepo = SessionHistoryRepository()
                replayRepo.loadHistory(messages)
                agentRepository = replayRepo
                observeRepositoryMessages(replayRepo)
            }

            is SessionHistoryRepository -> {
                repo.loadHistory(messages)
            }

            else -> {
                repo.loadHistory(messages)
            }
        }
    }

    private fun determineProjectBucket(): String {
        val projectDir = runCatching { IProjectManager.getInstance().projectDir }.getOrNull()
        val baseName = projectDir?.let {
            val displayName = it.name.takeIf { n -> n.isNotBlank() } ?: "project"
            val hashSuffix = it.absolutePath.hashCode().toString()
            "${displayName}_$hashSuffix"
        } ?: "default"
        return baseName.replace("[^A-Za-z0-9._-]".toRegex(), "_")
    }

    fun submitUserApproval(id: ApprovalId, decision: ReviewDecision) {
        agentRepository?.submitApprovalDecision(id, decision)
    }

    override fun onCleared() {
        agentRepository?.destroy()
        agentRepository = null
        saveJob?.cancel()
        timerJob?.cancel()
        repoMessagesJob?.cancel()
        repoExecEventsJob?.cancel()
        super.onCleared()
    }
}
