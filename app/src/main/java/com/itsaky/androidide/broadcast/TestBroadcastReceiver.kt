package com.itsaky.androidide.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.itsaky.androidide.activities.MainActivity

/**
 * Broadcast receiver for testing the AI assistant plugin via adb.
 * Automatically sends test prompts without any manual UI interaction.
 *
 * Example usage:
 * adb shell am broadcast -a com.itsaky.androidide.TEST_AI_PROMPT \
 *   --es prompt "I want a restaurant app with stock images"
 *
 * Optional extras:
 * --ez autoApprove true (auto-approve tool executions)
 *
 * How it works:
 * 1. Broadcast receiver receives the test prompt
 * 2. Stores prompt in SharedPreferences with AUTO_SEND flag
 * 3. Launches MainActivity (which ensures app is running)
 * 4. ChatFragment detects AUTO_SEND flag and sends message automatically
 * 5. No manual UI interaction required
 */
class TestBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "TestBroadcastReceiver received action: $action")

        when (action) {
            ACTION_TEST_AI_PROMPT -> handleTestPrompt(context, intent)
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    private fun handleTestPrompt(context: Context, intent: Intent) {
        val prompt = intent.getStringExtra(EXTRA_PROMPT)
        val autoApprove = intent.getBooleanExtra(EXTRA_AUTO_APPROVE, false)

        if (prompt.isNullOrBlank()) {
            Log.e(TAG, "Prompt is required for TEST_AI_PROMPT action")
            return
        }

        Log.i(TAG, "📝 Test prompt received: '$prompt'")
        Log.i(TAG, "🔒 Auto-approve: $autoApprove")

        // Store prompt in SharedPreferences for ChatFragment to pick up
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_PENDING_PROMPT, prompt)
            putBoolean(KEY_AUTO_SEND, true)
            putBoolean(KEY_AUTO_APPROVE, autoApprove)
            putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            apply()
        }

        Log.d(TAG, "✅ Prompt stored in SharedPreferences with AUTO_SEND flag")

        // Launch main activity - app will be running
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_TEST_AI_PROMPT
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        context.startActivity(launchIntent)
        Log.d(TAG, "🚀 MainActivity launched")
    }

    companion object {
        private const val TAG = "TestBroadcastReceiver"

        const val ACTION_TEST_AI_PROMPT = "com.itsaky.androidide.TEST_AI_PROMPT"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_AUTO_APPROVE = "autoApprove"

        // SharedPreferences keys for test communication
        const val PREFS_NAME = "test_ai_prefs"
        const val KEY_PENDING_PROMPT = "pending_prompt"
        const val KEY_AUTO_SEND = "auto_send"
        const val KEY_AUTO_APPROVE = "auto_approve"
        const val KEY_TIMESTAMP = "timestamp"

        /**
         * Check if there's a pending test prompt to send
         */
        fun getPendingPrompt(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_PENDING_PROMPT, null)
        }

        /**
         * Check if auto-send is enabled
         */
        fun shouldAutoSend(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_AUTO_SEND, false)
        }

        /**
         * Check if auto-approve is enabled
         */
        fun shouldAutoApprove(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_AUTO_APPROVE, false)
        }

        /**
         * Clear pending test prompt
         */
        fun clearPendingPrompt(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                remove(KEY_PENDING_PROMPT)
                remove(KEY_AUTO_SEND)
                remove(KEY_AUTO_APPROVE)
                remove(KEY_TIMESTAMP)
                apply()
            }
        }
    }
}
