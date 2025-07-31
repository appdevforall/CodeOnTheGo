package com.itsaky.androidide.app

import android.content.res.AssetManager

/**
 * A singleton object to manage the lifecycle and interaction with the native
 * llama.cpp inference engine.
 */
object LlmInferenceEngine {

    // This init block is executed when the object is first accessed.
    // It loads the native shared library (.so file) into memory.
    // The name "android-ide-llm" must match the library name defined in CMakeLists.txt.
    init {
        System.loadLibrary("android-ide-llm")
    }

    /**
     * Initializes the LLM model from a file in the app's assets.
     * This is a potentially long-running operation and should be called from a background thread.
     *
     * @param assetManager The Android AssetManager instance, needed to access assets from native code.
     * @param modelPath The path to the GGUF model file within the assets directory.
     * @return A native pointer (as a Long) to the loaded model context. This pointer must be
     *         passed to other native functions. Returns 0 on failure.
     */
    external fun initModel(assetManager: AssetManager, modelPath: String): Long

    /**
     * Runs inference on the loaded model.
     * This is a computationally intensive and blocking operation that MUST be called
     * from a background thread.
     *
     * @param prompt The input text prompt for the model.
     * @param contextPtr The native pointer to the model context, obtained from initModel().
     * @return The generated text completion as a String.
     */
    external fun runInference(prompt: String, contextPtr: Long): String

    /**
     * Releases the native memory allocated for the model context.
     * This must be called when the model is no longer needed to prevent memory leaks.
     *
     * @param contextPtr The native pointer to the model context to be released.
     */
    external fun releaseModel(contextPtr: Long)
}