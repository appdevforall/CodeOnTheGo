package com.itsaky.androidide.agent.api

import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.tool.shell.ShellCommandResult

interface IdeToolingApi {
    fun createFile(path: String, content: String): ToolResult
    fun readFile(path: String, offset: Int?, limit: Int?): ToolResult
    fun readFileContent(path: String, offset: Int?, limit: Int?): Result<String>
    fun updateFile(path: String, content: String): ToolResult
    fun deleteFile(path: String): ToolResult
    fun listFiles(path: String, recursive: Boolean): ToolResult
    fun searchProject(
        query: String,
        path: String?,
        maxResults: Int,
        ignoreCase: Boolean
    ): ToolResult

    fun addDependency(dependencyString: String, buildFilePath: String): ToolResult
    fun addStringResource(name: String, value: String): ToolResult
    suspend fun runApp(): ToolResult
    suspend fun triggerGradleSync(): ToolResult
    fun getBuildOutput(): ToolResult
    fun getBuildOutputContent(): String?
    suspend fun executeShellCommand(command: String): ShellCommandResult
    fun getDeviceBattery(): ToolResult
    fun getCurrentDateTime(): ToolResult
    fun getWeather(city: String?): ToolResult
}
