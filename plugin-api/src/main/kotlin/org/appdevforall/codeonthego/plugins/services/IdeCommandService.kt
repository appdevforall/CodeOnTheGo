package org.appdevforall.codeonthego.plugins.services

import org.appdevforall.codeonthego.plugins.extensions.CommandOutput
import org.appdevforall.codeonthego.plugins.extensions.CommandResult
import org.appdevforall.codeonthego.plugins.extensions.CommandSpec
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