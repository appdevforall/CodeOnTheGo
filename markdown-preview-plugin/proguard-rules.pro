# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# Keep Markwon classes
-keep class io.noties.markwon.** { *; }
-keep class org.commonmark.** { *; }

# Keep plugin classes
-keep class com.codeonthego.markdownpreviewer.** { *; }
