-ignorewarnings

-dontwarn **
-dontnote **
-dontobfuscate

# ADFA-3604: R8's optimize pass (inlining/method merging) can silently
# retarget a call to the wrong method when it fails to parse Kotlin 2.3.0
# metadata (see "R8: An error occurred when parsing kotlin metadata" in the
# build log) -- observed corrupting calls to utils.TestModeUtilsKt.isTestMode
# into calls to the unrelated, device-missing dalvik.system.VMRuntime.isTestMode,
# crashing every release build on launch. Shrinking (dead-code removal) is
# what actually cuts dex size; the optimize passes are the risky part given
# this R8/Kotlin version mismatch, so disable only optimization.
-dontoptimize

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

# lsp/kotlin registers its own IntelliJ project/application services -- some
# by class name in lsp/kotlin/src/main/resources/META-INF/kt-lsp/kt-lsp.xml,
# the rest via ::class literals in
# lsp/kotlin/.../registrar/AnalysisApiServiceProviders.kt, which PicoContainer
# then instantiates reflectively via each class's no-arg constructor. A
# ::class literal doesn't count as an actual `new` call to R8, so it kept
# stripping "unused" no-arg constructors one class at a time as each was
# discovered on-device (ClassNotFoundException on DirectInheritorsProvider,
# then a PicoInitializationException on ModuleDependentsProvider's missing
# constructor). Keep the whole package rather than list every implementation
# class in AnalysisApiServiceProviders.kt individually.
-keep class com.itsaky.androidide.lsp.kotlin.compiler.services.** { *; }

# Kotlin Analysis API (bundled in subprojects/kotlin-analysis-api, used by the
# Kotlin LSP). subprojects/kotlin-analysis-api/consumer-rules.pro keeps every
# class this jar's own IntelliJ plugin XML descriptors and ServiceLoader
# entries reference by name, but on-device testing kept surfacing distinct
# reflection paths that narrow list didn't cover -- not just inside this jar,
# but in other lsp/kotlin runtime dependencies too (Caffeine picks a cache
# implementation from dozens of codegenned variant classes at runtime; a
# protobuf-lite message field is resolved by name string; lsp/kotlin's own
# kt-lsp.xml above; and a NullPointerException deep in IntelliJ's own
# JavaCoreApplicationEnvironment bootstrap, verified absent on an unshrunk
# debug build). Each fix was quick but the next gap kept appearing elsewhere
# in the same dependency graph, so rather than keep discovering them one
# on-device crash at a time, keep every runtime dependency lsp/kotlin pulls in
# whole. This gives up the dex-size reduction for this whole dependency graph;
# see ADFA-3604 for the size trade-off.
-keep class org.jetbrains.kotlin.** { *; }
-keep class com.github.benmanes.caffeine.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.script.** { *; }
-keep class kotlinx.coroutines.internal.** { *; }
-keep class one.util.streamex.** { *; }
-keep class gnu.trove.** { *; }

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

# Protobuf lite: the generated runtime resolves message fields by name via
# reflection (the info string baked into newMessageInfo()), so shrinking a
# "field with no direct bytecode reference" breaks it at runtime with a
# NoSuchFieldException (observed on SyncMetaModels$SyncMeta.projectModelInfo_).
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

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

## GlitchTip crash reporting (via the Sentry SDK; GlitchTip speaks the Sentry protocol)
-keepattributes SourceFile,LineNumberTable
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

## Plugin SPI
## Plugins are loaded dynamically via DexClassLoader, so R8 cannot see their
## implementations of these interfaces. Without these rules, R8 narrows the
## return types of default interface methods (e.g. UIExtension.getEditorTabs
## returning emptyList()) to EmptyList, then inserts CHECKCAST at call sites.
## When a plugin returns a non-EmptyList (listOf(x), mutableListOf(), etc),
## the cast fails at runtime.
-keep class com.itsaky.androidide.plugins.** { *; }
-keep interface com.itsaky.androidide.plugins.** { *; }

## Initial rules to enable when R8 is shrinking to address exceptions
#-keep class com.sun.tools.jdi.** { *; }
#-keep class com.sun.jdi.** { *; }

## R8 Kotlin metadata workaround for Kotlin 2.3.0 compatibility
## Suppresses D8 errors when parsing kotlin metadata for StopWatch inline functions
-keep class com.itsaky.androidide.utils.StopWatch { *; }
-keepclassmembers class com.itsaky.androidide.utils.StopWatch** { *; }

