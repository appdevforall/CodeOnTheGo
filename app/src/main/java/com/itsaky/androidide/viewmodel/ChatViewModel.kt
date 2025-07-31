package com.itsaky.androidide.viewmodel

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.actions.sidebar.models.ChatMessage
import com.itsaky.androidide.data.repository.GeminiRepository
import com.itsaky.androidide.models.ChatSession
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChatViewModel(
    private val geminiRepository: GeminiRepository
) : ViewModel() {
    private val _sessions = MutableLiveData<MutableList<ChatSession>>(mutableListOf())
    val sessions: LiveData<MutableList<ChatSession>> = _sessions

    private val _currentSession = MutableLiveData<ChatSession?>()
    val currentSession: LiveData<ChatSession?> = _currentSession

    private val gson = Gson()

    companion object {
        private const val CHAT_HISTORY_LIST_PREF_KEY = "chat_history_list_v1"
        private const val CURRENT_CHAT_ID_PREF_KEY = "current_chat_id_v1"
    }


    /**
     * Sends a message.
     * @param fullPrompt The complete prompt to send to the AI, which may include context.
     * @param originalUserText The original text the user typed, for display in the chat history.
     */
    fun sendMessage(fullPrompt: String, originalUserText: String) {
        val userMessage = ChatMessage(originalUserText, ChatMessage.Sender.USER)
        addMessageToCurrentSession(userMessage)
        retrieveAgentResponse(fullPrompt)
    }

    /**
     * Overloaded function for simple messages without extra context.
     */
    fun sendMessage(text: String) {
        sendMessage(fullPrompt = text, originalUserText = text)
    }


    private fun addMessageToCurrentSession(message: ChatMessage) {
        val session = _currentSession.value ?: return
        session.messages.add(message)
        _currentSession.postValue(session) // Use postValue to ensure thread safety
    }

    private val chatScope = CoroutineScope(Dispatchers.Default + CoroutineName("IDEEditorChat"))

    private fun retrieveAgentResponse(prompt: String) {
        chatScope.launch {
            val response = geminiRepository.generateASimpleResponse(prompt)
            val agentResponse = ChatMessage(
                text = response,
                sender = ChatMessage.Sender.AGENT
            )
            addMessageToCurrentSession(agentResponse)
        }
    }

    // ... rest of the ViewModel remains the same ...
    fun loadSessions(prefs: SharedPreferences) {
        val json = prefs.getString(CHAT_HISTORY_LIST_PREF_KEY, null)
        val loadedSessions: MutableList<ChatSession> = if (json != null) {
            val type = object : TypeToken<MutableList<ChatSession>>() {}.type
            gson.fromJson(json, type)
        } else {
            mutableListOf()
        }

        if (loadedSessions.isEmpty()) {
            loadedSessions.add(ChatSession())
        }

        _sessions.value = loadedSessions

        val currentId = prefs.getString(CURRENT_CHAT_ID_PREF_KEY, null)
        _currentSession.value = loadedSessions.find { it.id == currentId } ?: loadedSessions.first()
    }

    fun saveSessions(prefs: SharedPreferences) {
        val json = gson.toJson(_sessions.value)
        prefs.edit { putString(CHAT_HISTORY_LIST_PREF_KEY, json) }

        _currentSession.value?.let {
            prefs.edit { putString(CURRENT_CHAT_ID_PREF_KEY, it.id) }
        }
    }

    fun createNewSession() {
        val newSession = ChatSession()
        _sessions.value?.add(0, newSession)
        _currentSession.value = newSession
    }

    fun setCurrentSession(sessionId: String) {
        val session = _sessions.value?.find { it.id == sessionId }
        if (session != null) {
            _currentSession.value = session
        }
    }
}