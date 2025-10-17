package com.itsaky.androidide.agent.prompt

import android.content.Context
import android.util.Log

/**
 * Lazily loads the base system prompt for the agent from the assets folder. If the asset cannot be
 * found, the provider falls back to an internal, simpler prompt so the agent can still operate.
 */
object SystemPromptProvider {

    private const val DEFAULT_ASSET_PATH = "agent/system_prompt.txt"
    private const val GPT5_ASSET_PATH = "agent/gpt_5_system_prompt.txt"
    private val lock = Any()

    @Volatile
    private var cachedPrompts: MutableMap<String, String> = mutableMapOf()

    fun get(context: Context, modelFamilyId: String? = null): String {
        val assetCandidates = buildList {
            if (modelFamilyId != null && isGpt5Family(modelFamilyId)) {
                add(GPT5_ASSET_PATH)
            }
            add(DEFAULT_ASSET_PATH)
        }

        assetCandidates.forEachIndexed { index, assetPath ->
            loadPrompt(context, assetPath)?.let { return it }
            if (index == assetCandidates.lastIndex) {
                Log.w(
                    "SystemPromptProvider",
                    "Failed to load $assetPath from assets. Falling back to internal prompt."
                )
            }
        }

        return FALLBACK_PROMPT
    }

    private fun isGpt5Family(modelFamilyId: String): Boolean {
        val normalized = modelFamilyId.lowercase()
        return normalized.contains("gpt5") || normalized.contains("gpt-5")
    }

    private fun loadPrompt(context: Context, assetPath: String): String? {
        cachedPrompts[assetPath]?.let { return it }
        synchronized(lock) {
            cachedPrompts[assetPath]?.let { return it }
            val prompt = runCatching {
                context.assets.open(assetPath).bufferedReader().use { it.readText() }
            }.getOrNull() ?: return null
            val normalizedPrompt = prompt.trimEnd()
            cachedPrompts[assetPath] = normalizedPrompt
            return normalizedPrompt
        }
    }

    private val FALLBACK_PROMPT = """
        You are an AndroidIDE automation agent. Always investigate the project structure before
        attempting to modify files. Use `list_dir` to inspect directories and `read_file` to open
        individual files. Produce plans, execute them step by step, and communicate your progress
        clearly to the user. Avoid making assumptions about the project type without confirming it.
    """.trimIndent()
}
