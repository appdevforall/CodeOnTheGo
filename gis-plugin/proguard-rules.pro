# Add project specific ProGuard rules here.

-keepattributes SourceFile,LineNumberTable

# Keep plugin classes
-keep class com.codeonthego.gisplugin.** { *; }

# Keep AndroidIDE plugin API classes
-keep class com.itsaky.androidide.plugins.** { *; }
-dontwarn com.itsaky.androidide.plugins.**
