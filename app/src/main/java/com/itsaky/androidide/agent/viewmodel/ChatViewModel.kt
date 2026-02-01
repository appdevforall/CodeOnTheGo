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
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.ChatSession
import com.itsaky.androidide.agent.MessageStatus
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.data.ChatStorageManager
import com.itsaky.androidide.agent.repository.AgenticRunner
import com.itsaky.androidide.agent.repository.AiBackend
import com.itsaky.androidide.agent.repository.GeminiRepository
import com.itsaky.androidide.agent.repository.LlmInferenceEngineProvider
import com.itsaky.androidide.agent.repository.LocalLlmRepositoryImpl
import com.itsaky.androidide.agent.repository.PREF_KEY_AI_BACKEND
import com.itsaky.androidide.agent.repository.PREF_KEY_LOCAL_MODEL_PATH
import com.itsaky.androidide.agent.repository.PREF_KEY_LOCAL_MODEL_SHA256
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class BackendStatus(
	val displayText: String,
)

class ChatViewModel : ViewModel() {
	private val log = LoggerFactory.getLogger(ChatViewModel::class.java)

	// --- State Exposure ---
	private val _sessions = MutableLiveData<MutableList<ChatSession>>(mutableListOf())
	val sessions: LiveData<MutableList<ChatSession>> = _sessions
	private val _currentSession = MutableLiveData<ChatSession?>()
	val currentSession: LiveData<ChatSession?> = _currentSession

	// A flow that holds the current, active repository instance
	private val repository = MutableStateFlow<GeminiRepository?>(null)

	// The public chatMessages flow now switches its subscription to the latest repository's flow.
	// This is the single source of truth for the UI.
	@OptIn(ExperimentalCoroutinesApi::class)
	val chatMessages: StateFlow<List<ChatMessage>> =
		repository
			.flatMapLatest { repo ->
				repo?.messages ?: flowOf(emptyList())
			}.stateIn(
				scope = viewModelScope,
				started = SharingStarted.WhileSubscribed(5000L),
				initialValue = emptyList(),
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
		get() = repository.value
		set(value) {
			repository.value = value
		}
	private var agentJob: Job? = null
	private var saveJob: Job? = null
	private var timerJob: Job? = null
	private var repoMessagesJob: Job? = null
	private var operationStartTime: Long = 0
	private var stepStartTime: Long = 0
	private val chatStorageManager: ChatStorageManager
	private var lastKnownBackendName: String? = null
	private var lastKnownModelPath: String? = null

	companion object {
		private const val CURRENT_CHAT_ID_PREF_KEY = "current_chat_id_v1"
		private const val SAVE_DEBOUNCE_MS = 500L
	}

	init {
		val baseDir = IProjectManager.getInstance().projectDir
		val agentDir = File(baseDir, "agent")
		chatStorageManager = ChatStorageManager(agentDir)
	}

    private suspend fun getOrCreateRepository(context: Context): GeminiRepository? {
		val prefs = BaseApplication.baseInstance.prefManager
		val backendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)
		val modelPath = prefs.getString(PREF_KEY_LOCAL_MODEL_PATH, null)

		// If the repository exists and settings haven't changed, return the existing instance.
		if (agentRepository != null && lastKnownBackendName == backendName && lastKnownModelPath == modelPath) {
			return agentRepository
		}

		log.info("Settings changed or repository not initialized. Creating new instance.")
		lastKnownBackendName = backendName
		lastKnownModelPath = modelPath
		val backend = AiBackend.valueOf(backendName ?: "GEMINI")

		agentRepository =
			when (backend) {
				AiBackend.GEMINI -> {
					log.info("Creating new AgenticRunner (Gemini) instance.")
					AgenticRunner(context).apply {
						onStateUpdate = { _agentState.value = it }
					}
				}

				AiBackend.LOCAL_LLM -> {
					// Get the SINGLE, SHARED instance of the engine
					val engine = LlmInferenceEngineProvider.instance

                    val expectedModelPath = modelPath?.trim().orEmpty()
                    if (expectedModelPath.isBlank()) {
                        log.error("Initialization failed: Local LLM model path is not set.")
                        null
					} else {
                        run {
                            val needsReload =
                                !engine.isModelLoaded || engine.loadedModelPath != expectedModelPath
                            if (needsReload) {
                                val expectedHash =
                                    prefs.getString(PREF_KEY_LOCAL_MODEL_SHA256, null)
                                val loaded = withContext(Dispatchers.IO) {
                                    engine.initModelFromFile(
                                        context,
                                        expectedModelPath,
                                        expectedHash
                                    )
                                }
                                if (!loaded) {
                                    log.error("Initialization failed: Local LLM model load failed.")
                                    return@run null
                                }
                            }

                            log.info("Creating LocalLlmRepositoryImpl with shared, pre-loaded engine.")
                            LocalLlmRepositoryImpl(context, engine).apply {
                                onStateUpdate = { _agentState.value = it }
                            }
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
		val prefs = BaseApplication.baseInstance.prefManager
		val currentBackendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)!!
		val currentModelPath = prefs.getString(PREF_KEY_LOCAL_MODEL_PATH, null)
		val backend = AiBackend.valueOf(currentBackendName)

		val configChanged =
			currentBackendName != lastKnownBackendName || currentModelPath != lastKnownModelPath
		if (configChanged) {
			agentRepository?.stop()
			agentRepository = null
			observeRepositoryMessages(null)
		}

		val displayText = buildBackendDisplayText(backend, currentModelPath, context)
		_backendStatus.value = BackendStatus(displayText)

		lastKnownBackendName = currentBackendName
		lastKnownModelPath = currentModelPath
	}

	private fun buildSystemMessage(
		backend: AiBackend,
		modelPath: String?,
		context: Context,
	): String {
		val backendDisplayName =
			backend.name
				.replace("_", " ")
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

	fun sendMessage(
		fullPrompt: String,
		originalUserText: String,
		context: Context,
	) {
		val currentState = _agentState.value
		if (currentState is AgentState.Processing || currentState is AgentState.Cancelling) {
			log.warn("sendMessage called while agent was busy. Ignoring.")
			return
		}

		if (currentState is AgentState.Error) {
			_agentState.value = AgentState.Idle
		}

		_agentState.value = AgentState.Processing("Thinking...")

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
		context: Context,
	) {
		agentJob =
			viewModelScope.launch {
				startTimer()
				log.info("Starting agent workflow for prompt: \"{}\"", originalUserPrompt)
				try {
					// Now we just get the stable repository instance.
					val repository = getOrCreateRepository(context)
					if (repository == null) {
						log.error("Aborting workflow: AI repository failed to initialize.")
						val backendName = lastKnownBackendName
						if (backendName == AiBackend.LOCAL_LLM.name) {
							postSystemError(
								"Local model is not loaded. Open AI Settings, pick a model, and try again.",
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
							history.size,
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
		context: Context,
	): String =
		when (backend) {
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

	fun stopAgentResponse() {
		_agentState.value = AgentState.Cancelling
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
		agentRepository?.loadHistory(session.messages)
	}

	private fun postSystemError(message: String) {
		val session = _currentSession.value ?: return
		val errorMessage =
			ChatMessage(
				text = message,
				sender = Sender.SYSTEM,
				status = MessageStatus.ERROR,
			)
		session.messages.add(errorMessage)
		_sessions.value = _sessions.value
		agentRepository?.loadHistory(session.messages)
		scheduleSaveCurrentSession()
		_agentState.value = AgentState.Error(message)
	}

	fun saveAllSessionsAndState(prefs: SharedPreferences) {
		saveJob?.cancel()
        val currentSession = _currentSession.value
        val sessions = _sessions.value
        viewModelScope.launch(Dispatchers.IO) {
            currentSession?.let { chatStorageManager.saveSession(it) }
            sessions?.let { chatStorageManager.saveAllSessions(it) }
            currentSession?.let {
                prefs.edit { putString(CURRENT_CHAT_ID_PREF_KEY, it.id) }
            }
		}
	}

	fun createNewSession() {
		val newSession = ChatSession()
		_sessions.value?.add(0, newSession)
		_sessions.postValue(_sessions.value)
		_currentSession.value = newSession
		agentRepository?.loadHistory(newSession.messages)
		observeRepositoryMessages(agentRepository)
		scheduleSaveCurrentSession()
	}

	fun setCurrentSession(sessionId: String) {
		saveJob?.cancel()
        val previousSession = _currentSession.value
        viewModelScope.launch(Dispatchers.IO) {
            previousSession?.let { chatStorageManager.saveSession(it) }
        }
		val session = _sessions.value?.find { it.id == sessionId }
		if (session != null) {
			_currentSession.value = session
			agentRepository?.loadHistory(session.messages)
			observeRepositoryMessages(agentRepository)
		}
	}

	private fun scheduleSaveCurrentSession() {
		saveJob?.cancel()
		saveJob =
            viewModelScope.launch(Dispatchers.IO) {
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
		timerJob =
			viewModelScope.launch {
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
		if (repo == null) {
			return
		}
		repoMessagesJob =
			viewModelScope.launch {
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
	}
}
