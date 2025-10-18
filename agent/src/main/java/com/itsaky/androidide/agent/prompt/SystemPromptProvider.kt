package com.itsaky.androidide.agent.prompt

import android.content.Context
import org.slf4j.LoggerFactory

/**
 * Lazily loads the base system prompt for the agent from the assets folder. If the asset cannot be
 * found, the provider falls back to an internal, simpler prompt so the agent can still operate.
 */
object SystemPromptProvider {

    private const val DEFAULT_ASSET_PATH = "agent/system_prompt.txt"
    private val lock = Any()
    private val logger = LoggerFactory.getLogger(SystemPromptProvider::class.java)

    @Volatile
    private var cachedPrompts: MutableMap<String, String> = mutableMapOf()

    fun get(context: Context, modelFamilyId: String? = null): String {
        val prompt = loadPrompt(context, DEFAULT_ASSET_PATH)
        if (prompt != null) {
            return prompt
        }
        logger.warn(
            "Failed to load {} from assets. Falling back to internal prompt.",
            DEFAULT_ASSET_PATH
        )
        return FALLBACK_PROMPT
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
