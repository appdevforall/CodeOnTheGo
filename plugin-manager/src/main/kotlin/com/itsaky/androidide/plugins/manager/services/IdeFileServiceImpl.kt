package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.IdeFileService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class IdeFileServiceImpl(
    private val pluginId: String,
    private val permissions: Set<PluginPermission>,
    private val pathValidator: PathValidator? = null
) : IdeFileService {

    interface PathValidator {
        fun isPathAllowed(path: File): Boolean
        fun getAllowedPaths(): List<String>
    }

    override fun readFile(file: File): String? {
        ensureWritePermission()
        ensurePathAllowed(file)

        return try {
            if (file.exists() && file.isFile) {
                file.readText()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun writeFile(file: File, content: String): Boolean {
        ensureWritePermission()
        ensurePathAllowed(file)

        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun appendToFile(file: File, content: String): Boolean {
        ensureWritePermission()
        ensurePathAllowed(file)

        return try {
            file.parentFile?.mkdirs()
            file.appendText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun insertAfterPattern(file: File, pattern: String, content: String): Boolean {
        ensureWritePermission()
        ensurePathAllowed(file)

        return try {
            val fileContent = file.readText()
            val index = fileContent.indexOf(pattern)

            if (index == -1) {
                return false
            }

            val insertionPoint = index + pattern.length
            val newContent = fileContent.substring(0, insertionPoint) + content + fileContent.substring(insertionPoint)

            file.writeText(newContent)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun replaceInFile(file: File, oldText: String, newText: String): Boolean {
        ensureWritePermission()
        ensurePathAllowed(file)

        return try {
            val fileContent = file.readText()
            val newContent = fileContent.replace(oldText, newText)

            if (fileContent != newContent) {
                file.writeText(newContent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun writeBinary(file: File, data: ByteArray): Boolean {
        ensureWritePermission()
        ensurePathAllowed(file)

        return try {
            file.parentFile?.mkdirs()
            file.writeBytes(data)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun writeStream(file: File, input: InputStream): Long {
        ensureWritePermission()
        ensurePathAllowed(file)

        return try {
            file.parentFile?.mkdirs()
            BufferedInputStream(input).use { buffered ->
                FileOutputStream(file).use { output ->
                    copyInterruptible(buffered, output)
                }
            }
        } catch (e: Exception) {
            FAILED_WRITE
        }
    }

    override fun delete(file: File): Boolean {
        ensureWritePermission()
        ensurePathAllowed(file)

        return try {
            if (!file.exists()) {
                true
            } else if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun copyInterruptible(input: InputStream, output: FileOutputStream): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (Thread.interrupted()) {
                throw InterruptedException("Stream copy interrupted")
            }
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        return total
    }

    private fun ensureWritePermission() {
        if (!hasAnyWritePermission()) {
            throw SecurityException(
                "Plugin $pluginId does not have required permissions: ${writePermissions.joinToString(", ") { it.name }}"
            )
        }
    }

    private fun ensurePathAllowed(path: File) {
        if (!isPathAllowed(path)) {
            throw SecurityException("Plugin $pluginId does not have access to path: ${path.absolutePath}")
        }
    }

    private fun hasAnyWritePermission(): Boolean =
        writePermissions.any { it in permissions }

    private fun isPathAllowed(path: File): Boolean {
        pathValidator?.let { validator ->
            return validator.isPathAllowed(path)
        }
        return PluginPathAllowlist.isAllowed(path, permissions, pluginId)
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val FAILED_WRITE = -1L
        val writePermissions = setOf(
            PluginPermission.FILESYSTEM_WRITE,
            PluginPermission.IDE_ENVIRONMENT_WRITE
        )
    }
}
