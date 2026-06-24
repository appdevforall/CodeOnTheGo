package com.itsaky.androidide.api.commands

import com.blankj.utilcode.util.FileIOUtils
import com.itsaky.androidide.projects.IProjectManager
import java.io.File

class ReadFileCommand(private val path: String) {
    fun execute(): Result<String> {
        return try {
            val targetFile = File(IProjectManager.getInstance().projectDir, path)
            when {
                !targetFile.exists() -> Result.failure(Exception("File not found at path: $path"))
                !targetFile.isFile -> Result.failure(Exception("Path does not point to a file: $path"))
                targetFile.isDirectory -> Result.failure(Exception("Path points to a directory: $path"))
                else -> {
                    val content = FileIOUtils.readFile2String(targetFile)
                    Result.success(content)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}