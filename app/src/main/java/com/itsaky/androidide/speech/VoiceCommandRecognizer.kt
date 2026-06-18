package com.itsaky.androidide.speech

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "VoiceCommandRecognizer"

/**
 * Represents a recognized voice intent.
 */
data class VoiceIntent(
    val name: String,
    val hasQuickAction: Boolean = false,
    val quickActionCommand: String = ""
)

/**
 * Recognizes programming intents from voice commands.
 *
 * Uses hardcoded patterns and fuzzy matching to detect:
 * - Code generation (explain, refactor, optimize, generate)
 * - Navigation (undo, redo, search, go to)
 * - Creation (create class, create function, create variable)
 * - Modification (delete, rename, format, comment)
 *
 * Does NOT use LLM for speed (50-100ms vs 500ms+ for LLM).
 */
class VoiceCommandRecognizer {

    companion object {
        private val INTENT_PATTERNS = mapOf(
            "EXPLAIN" to listOf("explain", "what is", "describe", "tell me about"),
            "REFACTOR" to listOf("refactor", "clean up", "improve", "optimize"),
            "CREATE_CLASS" to listOf("create class", "new class", "define class"),
            "CREATE_FUNCTION" to listOf("create function", "create fun", "new function", "define fun"),
            "CREATE_VARIABLE" to listOf("create variable", "new variable", "new var"),
            "DELETE" to listOf("delete", "remove", "erase"),
            "RENAME" to listOf("rename", "change name"),
            "FORMAT" to listOf("format", "beautify", "pretty"),
            "COMMENT" to listOf("comment", "uncomment"),
            "UNDO" to listOf("undo", "revert"),
            "REDO" to listOf("redo", "restore"),
            "SEARCH" to listOf("search", "find", "look for"),
            "GO_TO" to listOf("go to", "jump to", "navigate to")
        )

        private val QUICK_ACTIONS = mapOf(
            "UNDO" to "editor.undo()",
            "REDO" to "editor.redo()",
            "FORMAT" to "editor.format()",
            "DELETE" to "editor.delete()"
        )
    }

    /**
     * Recognize intent from transcribed text.
     *
     * @param text Transcribed voice command
     * @return VoiceIntent if recognized, null otherwise
     */
    suspend fun recognize(text: String): VoiceIntent? {
        return withContext(Dispatchers.Default) {
            try {
                val normalized = text.lowercase().trim()

                // Find best matching intent
                var bestMatch: String? = null
                var bestScore = 0f

                for ((intent, patterns) in INTENT_PATTERNS) {
                    for (pattern in patterns) {
                        val score = calculateSimilarity(pattern, normalized)
                        if (score > bestScore) {
                            bestScore = score
                            bestMatch = intent
                        }
                    }
                }

                if (bestScore >= 0.6f && bestMatch != null) {
                    Log.d(TAG, "Recognized intent: $bestMatch (score=$bestScore)")

                    val hasQuickAction = bestMatch in QUICK_ACTIONS
                    val quickCommand = QUICK_ACTIONS[bestMatch] ?: ""

                    return@withContext VoiceIntent(
                        name = bestMatch,
                        hasQuickAction = hasQuickAction,
                        quickActionCommand = quickCommand
                    )
                } else {
                    Log.d(TAG, "No intent recognized (best score: $bestScore)")
                    return@withContext null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Intent recognition error", e)
                null
            }
        }
    }

    /**
     * Calculate similarity score between two strings.
     * Uses simple substring matching for speed.
     *
     * @return Score from 0.0 to 1.0
     */
    private fun calculateSimilarity(pattern: String, text: String): Float {
        return when {
            text.contains(pattern) -> 1.0f
            pattern.any { text.contains(it) } -> 0.8f
            levenshteinDistance(pattern, text) <= 2 -> 0.7f
            else -> 0.0f
        }
    }

    /**
     * Calculate Levenshtein distance (edit distance) between two strings.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[s1.length][s2.length]
    }
}
