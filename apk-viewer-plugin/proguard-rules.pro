# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep plugin main class
-keep class com.example.sampleplugin.SamplePlugin { *; }

# Keep all plugin fragments
-keep class com.example.sampleplugin.fragments.** { *; }

# Keep plugin interfaces
-keep interface com.itsaky.androidide.plugins.** { *; }
-keep class com.itsaky.androidide.plugins.** { *; }

# Keep Android components
-keepclassmembers class * extends androidx.fragment.app.Fragment {
    public <init>(...);
}

# Keep reflection-based APIs
-keepattributes Signature
-keepattributes *Annotation*