package com.itsaky.androidide.api

import com.itsaky.androidide.api.commands.AddDependencyCommand
import com.itsaky.androidide.api.commands.AskUserCommand
import com.itsaky.androidide.api.commands.HighOrderCreateFileCommand
import com.itsaky.androidide.api.commands.HighOrderReadFileCommand
import com.itsaky.androidide.api.commands.ListFilesCommand
import com.itsaky.androidide.api.commands.RunBuildCommand
import com.itsaky.androidide.api.commands.UpdateFileCommand
import com.itsaky.androidide.data.model.ToolResult

/**
 * The single, clean entry point for the AI agent to interact with the IDE.
 * This Facade translates simple requests into executable Commands.
 */
object IDEApiFacade {

    fun createFile(path: String, content: String): ToolResult {
        val command = HighOrderCreateFileCommand(path, content)
        return command.execute()
    }

    fun runBuild(module: String?, variant: String): ToolResult {
        val command = RunBuildCommand(module, variant)
        return command.execute()
    }

    fun readFile(path: String) = HighOrderReadFileCommand(path).execute()
    fun listFiles(path: String, recursive: Boolean) = ListFilesCommand(path, recursive).execute()

    fun buildProject(): ToolResult = RunBuildCommand(module = null, variant = "debug").execute()
    fun runApp(): ToolResult {
        // This is a mock. A real implementation would trigger an app installation and launch process.
        return ToolResult(success = true, message = "App run command executed successfully.")
    }

    fun addDependency(dependencyString: String, buildFilePath: String): ToolResult {
        val finalPath = buildFilePath.ifEmpty { "app/build.gradle.kts" } // Default path
        return AddDependencyCommand(finalPath, dependencyString).execute()
    }

    fun askUser(question: String, options: List<String>) =
        AskUserCommand(question, options).execute()

    fun updateFile(path: String, content: String): ToolResult {
        val command = UpdateFileCommand(path, content)
        return command.execute()
    }

}