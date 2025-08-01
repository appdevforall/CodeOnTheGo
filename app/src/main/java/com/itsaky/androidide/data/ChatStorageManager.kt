package com.itsaky.androidide.data

import com.google.gson.Gson
import com.itsaky.androidide.models.ChatMessage
import com.itsaky.androidide.models.ChatSession
import java.io.File

/**
 * Manages saving and loading chat sessions to and from the file system.
 * Each session is stored as a .txt file in the given storage directory.
 * The file name is the session ID, and its content is a list of
 * newline-separated JSON strings, each representing a ChatMessage.
 */
class ChatStorageManager(private val storageDir: File) {

    private val gson = Gson()

    init {
        // Ensure the target directory exists.
        storageDir.mkdirs()
    }

    /**
     * Loads all chat sessions from .txt files in the storage directory.
     * Sessions are sorted by their last modification time, with the newest first.
     */
    fun loadAllSessions(): MutableList<ChatSession> {
        val sessionFiles =
            storageDir.listFiles { _, name -> name.endsWith(".txt") } ?: return mutableListOf()

        val sessions = sessionFiles.mapNotNull { file ->
            try {
                val sessionId = file.nameWithoutExtension
                val messages = file.readLines().mapNotNull { line ->
                    try {
                        gson.fromJson(line, ChatMessage::class.java)
                    } catch (e: Exception) {
                        // Log or ignore malformed lines
                        null
                    }
                }.toMutableList()

                ChatSession(id = sessionId, messages = messages)
            } catch (e: Exception) {
                // Log or handle file reading errors
                null
            }
        }

        // Sort sessions by the last modified date of their corresponding file, newest first.
        return sessions.sortedByDescending { session ->
            File(storageDir, "${session.id}.txt").lastModified()
        }.toMutableList()
    }

    /**
     * Saves a list of chat sessions to the storage directory.
     * It overwrites existing files for updated sessions and deletes files for removed sessions.
     */
    fun saveAllSessions(sessions: List<ChatSession>) {
        val currentSessionIds = sessions.map { it.id }.toSet()
        val existingFileIds = storageDir.listFiles { _, name -> name.endsWith(".txt") }
            ?.map { it.nameWithoutExtension }
            ?.toSet() ?: emptySet()

        // Save each session to its corresponding file.
        sessions.forEach { session ->
            val sessionFile = File(storageDir, "${session.id}.txt")
            val content = session.messages.joinToString("\n") { message ->
                gson.toJson(message)
            }
            sessionFile.writeText(content)
        }

        // Delete files for sessions that no longer exist.
        val sessionsToDelete = existingFileIds - currentSessionIds
        sessionsToDelete.forEach { sessionId ->
            File(storageDir, "$sessionId.txt").delete()
        }
    }
}