package com.itsaky.androidide.agent.repository

const val PREF_KEY_AI_BACKEND = "ai_backend_preference"
const val PREF_KEY_LOCAL_MODEL_PATH = "local_llm_model_path"
const val PREF_KEY_GEMINI_MODEL = "gemini_model_preference"
const val PREF_KEY_AGENT_USER_INSTRUCTIONS = "agent_user_instructions"
const val DEFAULT_GEMINI_MODEL = "gemini-2.5-pro"
val GEMINI_MODEL_OPTIONS = listOf(
    "gemini-2.5-pro",
    "gemini-2.5-flash",
    "gemini-2.0-pro-exp-02-05",
    "gemini-2.0-flash-exp",
    "gemini-1.5-pro",
    "gemini-1.5-flash"
)
