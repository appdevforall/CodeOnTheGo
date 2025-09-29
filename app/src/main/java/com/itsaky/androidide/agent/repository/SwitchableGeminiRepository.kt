package com.itsaky.androidide.agent.repository

import com.itsaky.androidide.agent.data.ToolCall
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.models.AgentState
import com.itsaky.androidide.models.ChatMessage

const val PREF_KEY_AI_BACKEND = "ai_backend_preference"
const val PREF_KEY_LOCAL_MODEL_PATH = "local_llm_model_path"

class SwitchableGeminiRepository(
    private val geminiRepository: AgenticRunner,
    private val localLlmRepository: LocalLlmRepositoryImpl,
) : GeminiRepository {

    private var activeRepository: GeminiRepository

    init {
        val prefs = BaseApplication.getBaseInstance().prefManager
        val backendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)
        activeRepository = when (AiBackend.valueOf(backendName ?: "GEMINI")) {
            AiBackend.LOCAL_LLM -> localLlmRepository
            else -> geminiRepository
        }
    }

    fun setActiveBackend(backend: AiBackend) {
        activeRepository = when (backend) {
            AiBackend.GEMINI -> geminiRepository
            AiBackend.LOCAL_LLM -> localLlmRepository
        }
        val prefs = BaseApplication.getBaseInstance().prefManager
        prefs.putString(PREF_KEY_AI_BACKEND, backend.name)
    }

    suspend fun loadLocalModel(uriString: String): Boolean {
        val prefs = BaseApplication.getBaseInstance().prefManager
        prefs.putString(PREF_KEY_LOCAL_MODEL_PATH, uriString)
        return localLlmRepository.loadModel(uriString)
    }

    override var onStateUpdate: ((AgentState) -> Unit)? = null
        set(value) {
            field = value
            geminiRepository.onStateUpdate = value
            localLlmRepository.onStateUpdate = value
        }

    override var onToolCall: ((ToolCall) -> Unit)? = null
        set(value) {
            field = value
            geminiRepository.onToolCall = value
            localLlmRepository.onToolCall = value
        }

    override var onToolMessage: ((String) -> Unit)? = null
        set(value) {
            field = value
            geminiRepository.onToolMessage = value
            localLlmRepository.onToolMessage = value
        }

    override var onAskUser: ((question: String, options: List<String>) -> Unit)? = null
        set(value) {
            field = value
            geminiRepository.onAskUser = value
            localLlmRepository.onAskUser = value
        }

    override fun getPartialReport() = activeRepository.getPartialReport()

    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ) = activeRepository.generateASimpleResponse(prompt, history)

    override suspend fun generateCode(
        prompt: String,
        fileContent: String,
        fileName: String,
        fileRelativePath: String
    ) = activeRepository.generateCode(prompt, fileContent, fileName, fileRelativePath)

}