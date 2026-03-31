package com.itsaky.androidide.agent.repository

import android.content.Context
import androidx.annotation.StringRes
import com.itsaky.androidide.agent.fragments.EncryptedPrefs
import com.itsaky.androidide.resources.R

sealed class BackendAvailability {
    object Available : BackendAvailability()
    data class Unavailable(@param:StringRes val messageRes: Int) : BackendAvailability()
}

fun getBackendAvailability(context: Context): BackendAvailability {
    return when (Util.getCurrentBackend()) {
        AiBackend.GEMINI -> if (EncryptedPrefs.getGeminiApiKey(context).isNullOrBlank()) {
            BackendAvailability.Unavailable(R.string.msg_ai_gemini_api_key_missing)
        } else {
            BackendAvailability.Available
        }

        AiBackend.LOCAL_LLM -> if (!LlmInferenceEngineProvider.instance.isModelLoaded) {
            BackendAvailability.Unavailable(R.string.msg_ai_local_model_not_loaded)
        } else {
            BackendAvailability.Available
        }
    }
}
