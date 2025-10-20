package com.itsaky.androidide.agent.events

import com.itsaky.androidide.agent.tool.shell.ParsedCommand

sealed interface ExecCommandEvent {
    val callId: String
}

data class ExecCommandBegin(
    override val callId: String,
    val command: String,
    val argv: List<String>,
    val parsedCommand: ParsedCommand
) : ExecCommandEvent

data class ExecCommandEnd(
    override val callId: String,
    val command: String,
    val argv: List<String>,
    val parsedCommand: ParsedCommand,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val formattedOutput: String,
    val sandboxFailureMessage: String?,
    val durationMillis: Long,
    val truncated: Boolean,
    val success: Boolean
) : ExecCommandEvent

interface ShellCommandEventEmitter {
    suspend fun onCommandBegin(event: ExecCommandBegin)
    suspend fun onCommandEnd(event: ExecCommandEnd)

    companion object {
        fun none(): ShellCommandEventEmitter = object : ShellCommandEventEmitter {
            override suspend fun onCommandBegin(event: ExecCommandBegin) = Unit
            override suspend fun onCommandEnd(event: ExecCommandEnd) = Unit
        }
    }
}
