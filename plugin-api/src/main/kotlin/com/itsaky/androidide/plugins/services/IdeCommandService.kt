package com.itsaky.androidide.plugins.services

import com.itsaky.androidide.plugins.extensions.CommandOutput
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import kotlinx.coroutines.flow.Flow

interface IdeCommandService {
    fun executeCommand(spec: CommandSpec, timeoutMs: Long = 600_000): CommandExecution
    fun isCommandRunning(executionId: String): Boolean
    fun cancelCommand(executionId: String): Boolean
    fun getRunningCommandCount(): Int
}

interface CommandExecution {
    val executionId: String
    val output: Flow<CommandOutput>
    suspend fun await(): CommandResult
    fun cancel()
}