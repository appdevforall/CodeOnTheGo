package com.itsaky.androidide.agent.data

import android.util.Log
import com.google.gson.Gson
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.ChatSession
import java.io.File

/**
 * Manages saving and loading chat sessions to and from the file system.
 * Each session is stored as a .txt file in the given storage directory.
 * The file name is the session ID, and its content is a list of
 * newline-separated JSON strings, each representing a ChatMessage.
 */
class ChatStorageManager(private val storageDir: File) {

    companion object {
        private const val TAG = "ChatStorageManager"
    }

    private val gson = Gson()

    init {
        // Ensure the target directory exists.
        storageDir.mkdirs()
    }

    private fun canPersist(sessions: List<ChatSession>): Boolean {
        val projectDir = storageDir.parentFile ?: return false

        if (!projectDir.exists() || !projectDir.isDirectory) {
            Log.w(TAG, "Project directory no longer exists. Skipping persistence.")
            return false
        }

        if (storageDir.exists() && storageDir.isDirectory) {
            return true
        }

        if (sessions.isNotEmpty()) {
            val recreated = storageDir.mkdirs()
            if (!recreated) {
                Log.w(TAG, "Failed to recreate agent directory.")
            }
            return recreated
        }

        Log.w(TAG, "No sessions in memory and agent dir missing. Skipping save.")
        return false
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
        if (!canPersist(sessions)) return

        val currentSessionIds = sessions.map { it.id }.toSet()
        val existingFileIds = storageDir.listFiles { _, name -> name.endsWith(".txt") }
            ?.map { it.nameWithoutExtension }
            ?.toSet() ?: emptySet()

        // Save each session to its corresponding file.
        sessions.forEach { session ->
            val sessionFile = File(storageDir, "${session.id}.txt")
            val content = session.messages.joinToString("\n") { gson.toJson(it) }
            sessionFile.writeText(content)
        }

        // Delete files for sessions that no longer exist.
        val sessionsToDelete = existingFileIds - currentSessionIds
        sessionsToDelete.forEach { sessionId ->
            File(storageDir, "$sessionId.txt").delete()
        }
    }

    /**
     * Saves a single chat session to its corresponding file.
     * This is more efficient than saveAllSessions for updating a single active chat.
     */
    fun saveSession(session: ChatSession) {
        if (!canPersist(listOf(session))) return

        try {
            val sessionFile = File(storageDir, "${session.id}.txt")
            // Serialize each message to a JSON string and join with newlines
            val content = session.messages.joinToString("\n") { gson.toJson(it) }
            sessionFile.writeText(content)
        } catch (e: Exception) {
            // It's good practice to handle potential I/O errors
            Log.e(TAG, "Error saving session ${session.id}", e)
        }
    }
}