# Keep the LLamaAndroid class and its public members from being removed or renamed.
-keep class android.llama.cpp.LLamaAndroid { *; }

# More specifically, ensure the static 'instance()' method is kept.
# This rule is more precise and often preferred.
-keep class android.llama.cpp.LLamaAndroid {
    public static *** instance();
}