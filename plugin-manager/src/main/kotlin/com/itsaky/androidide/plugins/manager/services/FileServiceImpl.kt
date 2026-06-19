package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeFileService.FileOperationResult
import java.io.File

/**
 * Implementation of [IdeFileService] for file operations within a project root.
 *
 * Security Notes:
 * - Path traversal prevention is enforced via [validateAndResolvePath]
 * - Plugin permission checks are delegated to PluginManager integration layer
 * - All file paths are validated against project root before operations
 */
class FileServiceImpl(
    private val projectRoot: File
) : IdeFileService {

    override fun readFile(relativePath: String): FileOperationResult {
        val resolvedFile = validateAndResolvePath(relativePath)
            ?: return FileOperationResult.failure("Path traversal detected: $relativePath")

        return if (resolvedFile.exists() && resolvedFile.isFile) {
            try {
                val content = resolvedFile.readText()
                FileOperationResult.success("File read successfully", content)
            } catch (e: Exception) {
                FileOperationResult.failure("Failed to read file: ${e.message}")
            }
        } else {
            FileOperationResult.failure("File does not exist or is not a file")
        }
    }

    override fun createFile(relativePath: String, content: String): FileOperationResult {
        val resolvedFile = validateAndResolvePath(relativePath)
            ?: return FileOperationResult.failure("Path traversal detected: $relativePath")

        return try {
            if (resolvedFile.exists()) {
                return FileOperationResult.failure("File already exists: $relativePath")
            }

            resolvedFile.parentFile?.mkdirs()
            resolvedFile.writeText(content)
            FileOperationResult.success("File created successfully", null)
        } catch (e: Exception) {
            FileOperationResult.failure("Failed to create file: ${e.message}")
        }
    }

    override fun updateFile(relativePath: String, content: String): FileOperationResult {
        val resolvedFile = validateAndResolvePath(relativePath)
            ?: return FileOperationResult.failure("Path traversal detected: $relativePath")

        return try {
            if (!resolvedFile.exists()) {
                return FileOperationResult.failure("File does not exist: $relativePath")
            }

            resolvedFile.writeText(content)
            FileOperationResult.success("File updated successfully", null)
        } catch (e: Exception) {
            FileOperationResult.failure("Failed to update file: ${e.message}")
        }
    }

    override fun deleteFile(relativePath: String): FileOperationResult {
        val resolvedFile = validateAndResolvePath(relativePath)
            ?: return FileOperationResult.failure("Path traversal detected: $relativePath")

        return try {
            if (!resolvedFile.exists()) {
                return FileOperationResult.success("File does not exist (already deleted)", null)
            }

            val deleted = if (resolvedFile.isDirectory) {
                resolvedFile.deleteRecursively()
            } else {
                resolvedFile.delete()
            }

            if (deleted) {
                FileOperationResult.success("File deleted successfully", null)
            } else {
                FileOperationResult.failure("Failed to delete file")
            }
        } catch (e: Exception) {
            FileOperationResult.failure("Failed to delete file: ${e.message}")
        }
    }

    override fun listFiles(relativePath: String, recursive: Boolean): FileOperationResult {
        val resolvedFile = validateAndResolvePath(relativePath)
            ?: return FileOperationResult.failure("Path traversal detected: $relativePath")

        return try {
            if (!resolvedFile.exists() || !resolvedFile.isDirectory) {
                return FileOperationResult.failure("Directory does not exist: $relativePath")
            }

            val files = if (recursive) {
                resolvedFile.walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(projectRoot).path }
                    .toList()
            } else {
                resolvedFile.listFiles()
                    ?.filter { it.isFile }
                    ?.map { it.name }
                    ?: emptyList()
            }

            val fileList = files.joinToString("\n")
            FileOperationResult.success("Listed ${files.size} files", fileList)
        } catch (e: Exception) {
            FileOperationResult.failure("Failed to list files: ${e.message}")
        }
    }

    override fun getProjectRoot(): File = projectRoot

    private fun validateAndResolvePath(relativePath: String): File? {
        // Validate null/empty paths
        if (relativePath.isNullOrBlank()) {
            return null
        }

        val file = File(projectRoot, relativePath)
        val canonicalPath = file.canonicalPath
        val rootCanonicalPath = projectRoot.canonicalPath

        // Security check: Ensure resolved path is within project root
        // Use path separator boundary check to prevent prefix matching attacks
        // e.g., /tmp/proj should not match /tmp/project/file.txt
        val isWithinRoot = canonicalPath == rootCanonicalPath ||
                (canonicalPath.startsWith(rootCanonicalPath + File.separator))

        return if (isWithinRoot) {
            file
        } else {
            null  // Path traversal attempt
        }
    }
}
