package com.itsaky.androidide.agent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.itsaky.androidide.agent.repository.LlmInferenceEngineProvider
import com.itsaky.androidide.agent.repository.PREF_KEY_LOCAL_MODEL_PATH
import com.itsaky.androidide.agent.repository.PREF_KEY_LOCAL_MODEL_SHA256
import com.itsaky.androidide.api.commands.VectorSearchTestCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Broadcast receiver for triggering LLM-related commands via ADB.
 *
 * Extensible design for multiple LLM features:
 * - Current: Vector search test
 * - Future: Feature B, Feature C, etc.
 *
 * Usage examples:
 *
 * 1. Vector Search Test:
 *    adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
 *      --es action "vector_search" \
 *      --es query "main" \
 *      --ei max_files 10
 *
 * 2. Future Feature B (placeholder):
 *    adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
 *      --es action "feature_b" \
 *      --es input "some_value"
 *
 * 3. Future Feature C with parameters (placeholder):
 *    adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
 *      --es action "feature_c" \
 *      --es param1 "value1" \
 *      --es param2 "value2"
 */
class LlmCommandReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_LLM_COMMAND = "com.itsaky.androidide.LLM_COMMAND"

        // Action types
        private const val ACTION_TYPE_VECTOR_SEARCH = "vector_search"
        private const val ACTION_TYPE_FILE_EXPLORER = "file_explorer"
        private const val ACTION_TYPE_FEATURE_B = "feature_b"  // Placeholder for future
        private const val ACTION_TYPE_FEATURE_C = "feature_c"  // Placeholder for future

        // Common parameter keys
        private const val EXTRA_ACTION = "action"

        // Vector search parameters
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_MAX_FILES = "max_files"

        // File explorer parameters
        private const val EXTRA_OPERATION = "operation"  // list, read, tree
        private const val EXTRA_PATH = "path"            // relative path from project root
        private const val EXTRA_PATTERN = "pattern"      // glob pattern for filtering
        private const val EXTRA_DEPTH = "depth"          // max depth for tree

        // Future feature parameters (placeholders)
        private const val EXTRA_INPUT = "input"
        private const val EXTRA_PARAM1 = "param1"
        private const val EXTRA_PARAM2 = "param2"
    }

    private val log = LoggerFactory.getLogger(LlmCommandReceiver::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            log.warn("LlmCommandReceiver: null context or intent")
            return
        }

        if (intent.action != ACTION_LLM_COMMAND) {
            log.warn("LlmCommandReceiver: unexpected action ${intent.action}")
            return
        }

        val action = intent.getStringExtra(EXTRA_ACTION)
        if (action.isNullOrBlank()) {
            log.error("LlmCommandReceiver: 'action' parameter is required")
            return
        }

        log.info("LlmCommandReceiver: received action='$action'")

        // Route to appropriate handler based on action type
        when (action) {
            ACTION_TYPE_VECTOR_SEARCH -> handleVectorSearch(context, intent)
            // ACTION_TYPE_FILE_EXPLORER -> handleFileExplorer(context, intent)  // TODO: Implement handleFileExplorer
            ACTION_TYPE_FEATURE_B -> handleFeatureB(context, intent)
            ACTION_TYPE_FEATURE_C -> handleFeatureC(context, intent)
            else -> {
                log.error("LlmCommandReceiver: unknown action type '$action'")
                log.error("Supported actions: $ACTION_TYPE_VECTOR_SEARCH, $ACTION_TYPE_FEATURE_B, $ACTION_TYPE_FEATURE_C")
            }
        }
    }

    /**
     * Handler for vector search test command.
     *
     * Parameters:
     * - query (required): Search query string
     * - max_files (optional): Maximum files to index (default: 10)
     */
    private fun handleVectorSearch(context: Context, intent: Intent) {
        val query = intent.getStringExtra(EXTRA_QUERY)
        if (query.isNullOrBlank()) {
            log.error("VectorSearch: 'query' parameter is required")
            log.info("Usage: adb shell am broadcast -a $ACTION_LLM_COMMAND --es action vector_search --es query \"your query\"")
            return
        }

        val maxFiles = intent.getIntExtra(EXTRA_MAX_FILES, 10)
        log.info("VectorSearch: query='$query', maxFiles=$maxFiles")

        scope.launch {
            try {
                val engine = LlmInferenceEngineProvider.instance

                // Ensure model is loaded (it's loaded lazily when first message is sent)
                if (!engine.isModelLoaded) {
                    log.info("VectorSearch: Model not loaded yet, loading now...")

                    // Get model path from preferences
                    val prefs = com.itsaky.androidide.app.BaseApplication.baseInstance.prefManager
                    val modelPath = prefs.getString(PREF_KEY_LOCAL_MODEL_PATH, null)
                    val modelHash = prefs.getString(PREF_KEY_LOCAL_MODEL_SHA256, null)

                    if (modelPath.isNullOrBlank()) {
                        log.error("VectorSearch: No model path configured in preferences")
                        log.error("VectorSearch: Please configure a model in AI Settings")
                        return@launch
                    }

                    log.info("VectorSearch: Loading model from: $modelPath")
                    val loaded = engine.initModelFromFile(context, modelPath, modelHash)

                    if (!loaded) {
                        log.error("VectorSearch: Failed to load model from: $modelPath")
                        return@launch
                    }

                    log.info("VectorSearch: Model loaded successfully: ${engine.loadedModelName}")
                } else {
                    log.info("VectorSearch: Model already loaded: ${engine.loadedModelName}")
                }

                log.info("VectorSearch: executing command...")
                val command = VectorSearchTestCommand(query, engine, maxFiles)
                val result = command.execute()

                if (result.success) {
                    log.info("VectorSearch: SUCCESS")
                    log.info("=".repeat(60))
                    log.info(result.data ?: "")
                    log.info("=".repeat(60))
                } else {
                    log.error("VectorSearch: FAILED - ${result.message}")
                    result.error_details?.let { log.error("Error details: $it") }
                }
            } catch (e: Exception) {
                log.error("VectorSearch: exception", e)
            }
        }
    }

    /**
     * Placeholder handler for future Feature B.
     *
     * Expected parameters:
     * - input (required): Input data for feature B
     *
     * Example usage:
     * adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
     *   --es action "feature_b" \
     *   --es input "some_value"
     */
    private fun handleFeatureB(context: Context, intent: Intent) {
        val input = intent.getStringExtra(EXTRA_INPUT)

        log.info("FeatureB: called with input='$input'")
        log.warn("FeatureB: NOT IMPLEMENTED YET - placeholder for future feature")

        // TODO: Implement Feature B
        // scope.launch {
        //     // Feature B implementation here
        // }
    }

    /**
     * Placeholder handler for future Feature C.
     *
     * Expected parameters:
     * - param1 (required): First parameter
     * - param2 (optional): Second parameter
     *
     * Example usage:
     * adb shell am broadcast -a com.itsaky.androidide.LLM_COMMAND \
     *   --es action "feature_c" \
     *   --es param1 "value1" \
     *   --es param2 "value2"
     */
    private fun handleFeatureC(context: Context, intent: Intent) {
        val param1 = intent.getStringExtra(EXTRA_PARAM1)
        val param2 = intent.getStringExtra(EXTRA_PARAM2)

        log.info("FeatureC: called with param1='$param1', param2='$param2'")
        log.warn("FeatureC: NOT IMPLEMENTED YET - placeholder for future feature")

        // TODO: Implement Feature C
        // scope.launch {
        //     // Feature C implementation here
        // }
    }
}
