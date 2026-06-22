# AI Code Helper Plugin ProGuard Rules

# Keep plugin entry point
-keep public class com.itsaky.androidide.plugins.aicodehelper.AiCodeHelperPlugin {
    public <methods>;
}

# Keep plugin-api interfaces
-keep interface com.itsaky.androidide.plugins.** { *; }

# Keep LlmInferenceService interfaces
-keep interface com.itsaky.androidide.plugins.services.LlmInferenceService** { *; }
