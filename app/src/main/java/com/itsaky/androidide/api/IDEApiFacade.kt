package com.itsaky.androidide.api

import com.itsaky.androidide.api.commands.CreateFileCommand
import com.itsaky.androidide.api.commands.RunBuildCommand
import java.io.File

/**
 * The single, clean entry point for the AI agent to interact with the IDE.
 * This Facade translates simple requests into executable Commands.
 */
object IDEApiFacade {

    fun createFile(path: String, content: String): Result<File> {
        val command = CreateFileCommand(path, content)
        return command.execute()
    }

    fun runBuild(module: String?, variant: String): Result<String> {
        val command = RunBuildCommand(module, variant)
        return command.execute()
    }

//    fun updateFile(path: String, newContent: String): Result<Unit> {
//        val command = UpdateFileCommand(path, newContent)
//        return command.execute()
//    }

    // Add other high-level functions: deleteFile, runTests, findTextInProject, etc.
}