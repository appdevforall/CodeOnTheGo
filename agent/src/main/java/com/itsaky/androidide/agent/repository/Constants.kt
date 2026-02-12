package com.itsaky.androidide.agent.repository

const val PREF_KEY_AI_BACKEND = "ai_backend_preference"
const val PREF_KEY_LOCAL_MODEL_PATH = "local_llm_model_path"
const val PREF_KEY_LOCAL_MODEL_SHA256 = "local_llm_model_sha256"
const val PREF_KEY_GEMINI_MODEL = "gemini_model_preference"
const val PREF_KEY_USE_SIMPLE_LOCAL_PROMPT = "use_simple_local_prompt"
const val PREF_KEY_AGENT_USER_INSTRUCTIONS = "agent_user_instructions"
const val DEFAULT_GEMINI_MODEL = "gemini-2.5-pro"
const val LOCAL_LLM_MODEL_ID = "local-llm"
val GEMINI_MODEL_OPTIONS = listOf(
    "gemini-2.5-pro",
    "gemini-2.5-flash",
    "gemini-2.0-pro-exp-02-05",
    "gemini-2.0-flash-exp",
    "gemini-1.5-pro",
    "gemini-1.5-flash"
)
