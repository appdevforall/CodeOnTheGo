package com.itsaky.androidide.api.commands

import com.blankj.utilcode.util.FileIOUtils
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import org.greenrobot.eventbus.EventBus
import java.io.File

class CreateFileCommand(
    private val baseDir: File,
    private val path: String,
    private val content: String
) {

    fun execute(): Result<File> {
        return try {
            val targetFile = File(baseDir, path)

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
