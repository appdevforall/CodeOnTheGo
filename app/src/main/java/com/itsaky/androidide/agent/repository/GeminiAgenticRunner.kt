package com.itsaky.androidide.agent.repository

import android.content.Context
import com.google.genai.types.Content
import com.google.genai.types.Tool
import com.itsaky.androidide.agent.fragments.EncryptedPrefs
import com.itsaky.androidide.agent.prompt.ModelFamily
import com.itsaky.androidide.agent.prompt.SystemPromptProvider
import org.slf4j.LoggerFactory

class GeminiAgenticRunner(
    private val appContext: Context,
    plannerModel: String = DEFAULT_GEMINI_MODEL,
    maxSteps: Int = 20,
    toolsOverride: List<Tool>? = null,
    plannerOverride: Planner? = null,
    criticOverride: Critic? = null,
    executorOverride: Executor? = null
) : BaseAgenticRunner(
    context = appContext,
    modelFamily = ModelFamily(
        id = plannerModel.ifBlank { DEFAULT_GEMINI_MODEL },
        baseInstructions = SystemPromptProvider.get(
            appContext,
            plannerModel.ifBlank { DEFAULT_GEMINI_MODEL }
        ),
        supportsParallelToolCalls = true,
        needsSpecialApplyPatchInstructions = true
    ),
    maxSteps = maxSteps,
    toolsOverride = toolsOverride,
    executorOverride = executorOverride
) {

    private val plannerModelName = plannerModel.ifBlank { DEFAULT_GEMINI_MODEL }

    private val plannerClient: GeminiClient by lazy {
        val apiKey = EncryptedPrefs.getGeminiApiKey(appContext)
        if (apiKey.isNullOrBlank()) {
            val errorMessage = "Gemini API Key not found. Please set it in the AI Settings."
            log.error(errorMessage)
            throw IllegalStateException(errorMessage)
        }
        GeminiClient(apiKey, plannerModelName)
    }

    private val criticClient: GeminiClient by lazy {
        val apiKey = EncryptedPrefs.getGeminiApiKey(appContext)
        if (apiKey.isNullOrBlank()) {
            val errorMessage = "Gemini API Key not found. Please set it in the AI Settings."
            log.error(errorMessage)
            throw IllegalStateException(errorMessage)
        }
        GeminiClient(apiKey, "gemini-2.5-flash")
    }

    private val planner: Planner = plannerOverride ?: Planner(plannerClient, tools)
    private val critic: Critic? = criticOverride ?: Critic(criticClient)

    override suspend fun createInitialPlan(history: List<Content>): Plan {
        return planner.createInitialPlan(history)
    }

    override suspend fun planForStep(history: List<Content>, plan: Plan, stepIndex: Int): Content {
        return planner.planForStep(history, plan, stepIndex)
    }

    override suspend fun processCriticStep(history: MutableList<Content>): String {
        val criticInstance = critic ?: return "OK"
        return runWithRetry("critic") {
            criticInstance.reviewAndSummarize(history)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(GeminiAgenticRunner::class.java)
    }
}
