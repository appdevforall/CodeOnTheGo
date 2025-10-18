package com.itsaky.androidide.agent

import com.itsaky.androidide.agent.api.IdeToolingApi
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.tool.ShellCommandRunner
import com.itsaky.androidide.agent.tool.TermuxShellCommandRunner
import com.itsaky.androidide.agent.tool.shell.ShellCommandResult
import com.itsaky.androidide.api.BuildOutputProvider
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.api.commands.ReadFileCommand

object AppIdeToolingApi : IdeToolingApi {
    private val shellRunner: ShellCommandRunner = TermuxShellCommandRunner()
    override fun createFile(path: String, content: String): ToolResult =
        IDEApiFacade.createFile(path, content)

    override fun readFile(path: String, offset: Int?, limit: Int?): ToolResult =
        IDEApiFacade.readFile(path, offset, limit)

    override fun readFileContent(path: String, offset: Int?, limit: Int?): Result<String> =
        ReadFileCommand(path, offset, limit).execute()

    override fun updateFile(path: String, content: String): ToolResult =
        IDEApiFacade.updateFile(path, content)

    override fun deleteFile(path: String): ToolResult =
        IDEApiFacade.deleteFile(path)

    override fun listFiles(path: String, recursive: Boolean): ToolResult =
        IDEApiFacade.listFiles(path, recursive)

    override fun searchProject(
        query: String,
        path: String?,
        maxResults: Int,
        ignoreCase: Boolean
    ): ToolResult = IDEApiFacade.searchProject(query, path, maxResults, ignoreCase)

    override fun addDependency(dependencyString: String, buildFilePath: String): ToolResult =
        IDEApiFacade.addDependency(dependencyString, buildFilePath)

    override fun addStringResource(name: String, value: String): ToolResult =
        IDEApiFacade.addStringResource(name, value)

    override suspend fun runApp(): ToolResult = IDEApiFacade.runApp()

    override suspend fun triggerGradleSync(): ToolResult = IDEApiFacade.triggerGradleSync()

    override fun getBuildOutput(): ToolResult = IDEApiFacade.getBuildOutput()

    override fun getBuildOutputContent(): String? = BuildOutputProvider.getBuildOutputContent()

    override suspend fun executeShellCommand(command: String): ShellCommandResult =
        shellRunner.run(command)
}
