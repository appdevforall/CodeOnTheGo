# AI Core Plugin ProGuard Rules

# Keep plugin entry point
-keep public class com.itsaky.androidide.plugins.aicore.AiCorePlugin {
    public <methods>;
}

# Keep LlmInferenceService implementation
-keep public class com.itsaky.androidide.plugins.aicore.LlmInferenceServiceImpl {
    public <methods>;
}

# Keep LocalLlmBackend
-keep public class com.itsaky.androidide.plugins.aicore.LocalLlmBackend {
    public <methods>;
}

# Keep plugin-api interfaces
-keep interface com.itsaky.androidide.plugins.** { *; }

# Keep llama-impl classes (if needed)
-keep class com.itsaky.llama.** { *; }
