package com.itsaky.androidide.agent.prompt

import android.content.Context
import com.itsaky.androidide.agent.repository.PREF_KEY_AGENT_USER_INSTRUCTIONS
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.utils.Environment

/**
 * Builds the synthetic context messages that seed the first turn of a new chat.
 */
object FirstTurnContextProvider {

    private const val APPROVAL_POLICY = "on-request"
    private const val SANDBOX_MODE = "workspace-write"
    private const val NETWORK_ACCESS = "restricted"

    fun buildEnvironmentContext(context: Context): String {
        val cwd = resolveWorkingDirectory(context)
        val shell = Environment.BASH_SHELL?.name ?: "bash"
        return buildString {
            appendLine("<environment_context>")
            appendLine("  <cwd>$cwd</cwd>")
            appendLine("  <approval_policy>$APPROVAL_POLICY</approval_policy>")
            appendLine("  <sandbox_mode>$SANDBOX_MODE</sandbox_mode>")
            appendLine("  <network_access>$NETWORK_ACCESS</network_access>")
            appendLine("  <shell>$shell</shell>")
            append("</environment_context>")
        }
    }

    fun wrapUserInstructions(instructions: String): String = buildString {
        appendLine("<user_instructions>")
        val normalized = instructions.trimEnd()
        if (normalized.isNotEmpty()) {
            appendLine(normalized)
        }
        append("</user_instructions>")
    }

    fun loadPersistentInstructions(): String? {
        val prefs = BaseApplication.getBaseInstance()?.prefManager
            ?: return null
        val raw = prefs.getString(PREF_KEY_AGENT_USER_INSTRUCTIONS) ?: return null
        return raw.takeIf { it.trim().isNotEmpty() }
    }

    private fun resolveWorkingDirectory(context: Context): String {
        val projectDir = runCatching { IProjectManager.getInstance().projectDir }
            .getOrNull()
            ?.takeIf { it.exists() && it.isDirectory }
        if (projectDir != null) {
            return projectDir.absolutePath
        }

        val defaultWorkspace = Environment.PROJECTS_DIR
        if (defaultWorkspace != null && defaultWorkspace.exists()) {
            return defaultWorkspace.absolutePath
        }

        return context.filesDir?.absolutePath ?: "/"
    }
}
