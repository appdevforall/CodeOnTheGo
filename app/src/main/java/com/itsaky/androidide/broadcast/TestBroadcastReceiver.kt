package com.itsaky.androidide.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.itsaky.androidide.activities.MainActivity

/**
 * Broadcast receiver for testing the AI assistant plugin via adb.
 *
 * Example usage:
 * adb shell am broadcast -a com.itsaky.androidide.TEST_AI_PROMPT \
 *   --es prompt "list my files"
 *
 * Optional extras:
 * --ez autoApprove true (auto-approve tool executions)
 * --es action "test_action" (custom test action)
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

        Log.i(TAG, "Test prompt: '$prompt', autoApprove: $autoApprove")

        // Launch main activity with test prompt
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_TEST_AI_PROMPT
            putExtra(EXTRA_PROMPT, prompt)
            putExtra(EXTRA_AUTO_APPROVE, autoApprove)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        context.startActivity(launchIntent)
    }

    companion object {
        private const val TAG = "TestBroadcastReceiver"

        const val ACTION_TEST_AI_PROMPT = "com.itsaky.androidide.TEST_AI_PROMPT"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_AUTO_APPROVE = "autoApprove"
    }
}
