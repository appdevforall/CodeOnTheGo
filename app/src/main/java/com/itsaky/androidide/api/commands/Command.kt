package com.itsaky.androidide.api.commands

import com.blankj.utilcode.util.FileIOUtils
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.builder.BuildService
import org.greenrobot.eventbus.EventBus
import java.io.File

interface Command<T> {
    fun execute(): Result<T>
}

class CreateFileCommand(private val path: String, private val content: String) : Command<File> {
    override fun execute(): Result<File> {
        return try {
            val projectDir = IProjectManager.getInstance().projectDir
            val targetFile = File(projectDir, path)

            if (targetFile.exists()) {
                throw IllegalStateException("File already exists at path: $path")
            }

            if (!FileIOUtils.writeFileFromString(targetFile, content)) {
                Result.failure(Exception("Failed to write to file at path: $path"))
            } else {
                EventBus.getDefault().post(FileCreationEvent(targetFile))
                Result.success(targetFile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}

// Concrete command for running a build
class RunBuildCommand(private val module: String?, private val variant: String) : Command<String> {
    override fun execute(): Result<String> {
        // Here you would access your BuildService directly, without an Activity.
        // This is a simplified example. You'll need to refactor your build logic
        // to be callable without a UI context.
        val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
        return if (buildService != null) {
            // ... logic to prepare and execute build ...
            Result.success("Build started successfully.")
        } else {
            Result.failure(Exception("BuildService is not available."))
        }
    }
}