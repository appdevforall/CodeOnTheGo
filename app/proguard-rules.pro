-ignorewarnings

-dontwarn **
-dontnote **
-dontobfuscate
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn com.itsaky.androidide.app.BaseIDEActivity
-dontwarn com.termux.shared.activity.ActivityUtils
-dontwarn com.termux.shared.android.PackageUtils
-dontwarn com.termux.shared.android.PermissionUtils
-dontwarn com.termux.shared.data.DataUtils
-dontwarn com.termux.shared.data.IntentUtils
-dontwarn com.termux.shared.errors.Errno
-dontwarn com.termux.shared.errors.Error
-dontwarn com.termux.shared.file.FileUtils
-dontwarn com.termux.shared.interact.MessageDialogUtils
-dontwarn com.termux.shared.net.uri.UriUtils
-dontwarn com.termux.shared.notification.NotificationUtils
-dontwarn com.termux.shared.shell.ShellUtils
-dontwarn com.termux.shared.shell.command.ExecutionCommand$Runner
-dontwarn com.termux.shared.shell.command.ExecutionCommand$ShellCreateMode
-dontwarn com.termux.shared.shell.command.ExecutionCommand
-dontwarn com.termux.shared.shell.command.environment.IShellEnvironment
-dontwarn com.termux.shared.shell.command.result.ResultConfig
-dontwarn com.termux.shared.shell.command.runner.app.AppShell$AppShellClient
-dontwarn com.termux.shared.shell.command.runner.app.AppShell
-dontwarn com.termux.shared.termux.TermuxConstants
-dontwarn com.termux.shared.termux.TermuxUtils$AppInfoMode
-dontwarn com.termux.shared.termux.TermuxUtils
-dontwarn com.termux.shared.termux.crash.TermuxCrashUtils
-dontwarn com.termux.shared.termux.file.TermuxFileUtils
-dontwarn com.termux.shared.termux.interact.TextInputDialogUtils$TextSetListener
-dontwarn com.termux.shared.termux.interact.TextInputDialogUtils
-dontwarn com.termux.shared.termux.plugins.TermuxPluginUtils
-dontwarn com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
-dontwarn com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
-dontwarn com.termux.shared.termux.shell.TermuxShellManager
-dontwarn com.termux.shared.termux.shell.TermuxShellUtils
-dontwarn com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
-dontwarn com.termux.shared.termux.shell.command.runner.terminal.TermuxSession$TermuxSessionClient
-dontwarn com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
-dontwarn com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
-dontwarn com.termux.shared.theme.NightMode
-dontwarn com.termux.shared.theme.ThemeUtils
-dontwarn com.termux.shared.view.ViewUtils
-dontwarn com.termux.terminal.TerminalSession
-dontwarn com.termux.terminal.TerminalSessionClient

-keep class javax.** { *; }
-keep class jdkx.** { *; }

# keep javac classes
-keep class openjdk.** { *; }

# Android builder model interfaces
-keep class com.android.** { *; }

# Tooling API classes
-keep class com.itsaky.androidide.tooling.** { *; }

# Builder model implementations
-keep class com.itsaky.androidide.builder.model.** { *; }

# Eclipse
-keep class org.eclipse.** { *; }

# JAXP
-keep class jaxp.** { *; }
-keep class org.w3c.** { *; }
-keep class org.xml.** { *; }

# Services
-keep @com.google.auto.service.AutoService class ** {
}
-keepclassmembers class ** {
    @com.google.auto.service.AutoService <methods>;
}

# EventBus
-keepclassmembers class ** {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
-keepclassmembers class * extends org.greenrobot.eventbus.util.ThrowableFailureEvent {
    <init>(java.lang.Throwable);
}

# Accessed reflectively
-keep class io.github.rosemoe.sora.widget.component.EditorAutoCompletion {
    io.github.rosemoe.sora.widget.component.EditorCompletionAdapter adapter;
    int currentSelection;
}
-keep class com.itsaky.androidide.projects.util.StringSearch {
    packageName(java.nio.file.Path);
}
-keep class * implements org.antlr.v4.runtime.Lexer {
    <init>(...);
}
-keep class * extends com.itsaky.androidide.lsp.java.providers.completion.IJavaCompletionProvider {
    <init>(...);
}
-keep class com.itsaky.androidide.editor.api.IEditor { *; }
-keep class * extends com.itsaky.androidide.inflater.IViewAdapter { *; }
-keep class * extends com.itsaky.androidide.inflater.drawable.IDrawableParser {
    <init>(...);
    android.graphics.drawable.Drawable parse();
    android.graphics.drawable.Drawable parseDrawable();
}
-keep class com.itsaky.androidide.utils.DialogUtils {  public <methods>; }

# APK Metadata
-keep class com.itsaky.androidide.models.ApkMetadata { *; }
-keep class com.itsaky.androidide.models.ArtifactType { *; }
-keep class com.itsaky.androidide.models.MetadataElement { *; }

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator CREATOR;
}

# Used in preferences
-keep enum org.eclipse.lemminx.dom.builder.EmptyElements { *; }
-keep enum com.itsaky.androidide.xml.permissions.Permission { *; }

# Lots of native methods in tree-sitter
# There are some fields as well that are accessed from native field
-keepclasseswithmembers class ** {
    native <methods>;
}

-keep class com.itsaky.androidide.treesitter.** { *; }

# Retrofit 2
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp3
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# Stat uploader
-keep class com.itsaky.androidide.stats.** { *; }

# Gson
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

## Retain generic signatures of TypeToken and its subclasses with R8 version 3.0 and higher.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

## Themes
-keep enum com.itsaky.androidide.ui.themes.IDETheme {
  *;
}

## Contributor models - deserialized with GSON
-keep class * implements com.itsaky.androidide.contributors.Contributor {
  *;
}