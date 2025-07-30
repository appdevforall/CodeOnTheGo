package com.itsaky.androidide.viewmodel

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.actions.sidebar.models.ChatMessage
import com.itsaky.androidide.models.ChatSession
import java.util.Locale

class ChatViewModel : ViewModel() {

    private val _sessions = MutableLiveData<MutableList<ChatSession>>(mutableListOf())
    val sessions: LiveData<MutableList<ChatSession>> = _sessions

    private val _currentSession = MutableLiveData<ChatSession>()
    val currentSession: LiveData<ChatSession> = _currentSession

    private val gson = Gson()

    companion object {
        private const val CHAT_HISTORY_LIST_PREF_KEY = "chat_history_list_v1"
        private const val CURRENT_CHAT_ID_PREF_KEY = "current_chat_id_v1"
    }

    fun loadSessions(prefs: SharedPreferences) {
        val json = prefs.getString(CHAT_HISTORY_LIST_PREF_KEY, null)
        val loadedSessions: MutableList<ChatSession> = if (json != null) {
            val type = object : TypeToken<MutableList<ChatSession>>() {}.type
            gson.fromJson(json, type)
        } else {
            mutableListOf()
        }

        if (loadedSessions.isEmpty()) {
            loadedSessions.add(ChatSession()) // Ensure there's always at least one session
        }

        _sessions.value = loadedSessions

        val currentId = prefs.getString(CURRENT_CHAT_ID_PREF_KEY, null)
        _currentSession.value = loadedSessions.find { it.id == currentId } ?: loadedSessions.first()
    }

    fun saveSessions(prefs: SharedPreferences) {
        val json = gson.toJson(_sessions.value)
        prefs.edit().putString(CHAT_HISTORY_LIST_PREF_KEY, json).apply()

        _currentSession.value?.let {
            prefs.edit().putString(CURRENT_CHAT_ID_PREF_KEY, it.id).apply()
        }
    }

    fun sendMessage(text: String) {
        // 1. Add user message
        val userMessage = ChatMessage(text, ChatMessage.Sender.USER)
        addMessageToCurrentSession(userMessage)

        // 2. Trigger simulated agent response
        simulateAgentResponse(text)
    }

    private fun addMessageToCurrentSession(message: ChatMessage) {
        val session = _currentSession.value ?: return
        session.messages.add(message)
        _currentSession.postValue(session) // Notify observers of the change
    }

    private fun simulateAgentResponse(originalText: String) {
        Handler(Looper.getMainLooper()).postDelayed({
            val agentResponse = ChatMessage(
                text = originalText.uppercase(Locale.getDefault()),
                sender = ChatMessage.Sender.AGENT
            )
            addMessageToCurrentSession(agentResponse)
        }, 1500) // 1.5-second delay
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