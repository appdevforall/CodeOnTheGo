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

# Gson model classes deserialized only via reflection (gson.fromJson(...,
# X::class.java)), same "R8 strips the unreachable constructor" issue as
# templates.impl.zip below. Covers OpenedFilesCache/OpenedFile (no prior
# rule) as well as the APK metadata classes already listed individually.
-keep class com.itsaky.androidide.models.** { *; }
-keep class com.itsaky.androidide.lsp.debug.model.** { *; }

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

# Gson
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Gson model classes: nothing calls `new TemplatesIndex(...)` directly --
# only gson.fromJson(..., TemplatesIndex::class.java) does, via reflection.
# With no traceable constructor call, R8 strips the constructor and Gson's
# runtime then reports the class as abstract ("Failed to load template
# archive ... Abstract classes can't be instantiated!").
-keep class com.itsaky.androidide.templates.impl.zip.** { *; }

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

# ADFA-5156: plugins load parent-first through a stock DexClassLoader, so they
# resolve kotlin.** from the app's dex rather than their own bundled stdlib. R8
# cannot see plugin call sites, so it strips every stdlib member the IDE itself
# does not call and plugins die with NoSuchMethodError at runtime (Sketch to UI:
# ArraysKt.maxOrNull([F)). ADFA-5156 first fixed this with -dontshrink, which
# made R8 a pass-through over the whole app to protect one library; ADFA-5195
# replaced it with the targeted keeps below, restoring dead-code removal
# everywhere else.
#
# ADFA-5164 narrows this: freeze the stdlib subset plugins may rely on and have
# plugins bundle the rest. Until then the whole stdlib is the contract, so these
# stay broad -- and note the keeps further up cover only kotlin.reflect,
# kotlin.script and kotlinx.coroutines.internal, not kotlin.collections/text/io/
# sequences, which is the hole maxOrNull([F) fell through.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

## Plugin SPI
## Plugins are loaded dynamically via DexClassLoader, so R8 cannot see their
## implementations of these interfaces. Without these rules, R8 narrows the
## return types of default interface methods (e.g. UIExtension.getEditorTabs
## returning emptyList()) to EmptyList, then inserts CHECKCAST at call sites.
## When a plugin returns a non-EmptyList (listOf(x), mutableListOf(), etc),
## the cast fails at runtime.
-keep class com.itsaky.androidide.plugins.** { *; }
-keep interface com.itsaky.androidide.plugins.** { *; }

## ADFA-3604: JDI's SocketAttachingConnector/SocketListeningConnector are
## loaded via ServiceLoader (META-INF/services), which R8 can't trace, so it
## stripped their no-arg constructors. This surfaced as
## "ServiceConfigurationError: Provider ... could not be instantiated" ->
## "java.lang.Error: no Connectors loaded" from VirtualMachineManagerImpl,
## which the app then reports to the user as a generic "Network access
## error" (the debug-connect failure handler always appends a network
## suggestion regardless of cause) even though this has nothing to do with
## network permissions. This is exactly the exceptions these rules were
## anticipating -- enabling them now that shrinking is genuinely on.
-keep class com.sun.tools.jdi.** { *; }
-keep class com.sun.jdi.** { *; }

## R8 Kotlin metadata workaround for Kotlin 2.3.0 compatibility
## Suppresses D8 errors when parsing kotlin metadata for StopWatch inline functions
-keep class com.itsaky.androidide.utils.StopWatch { *; }
-keepclassmembers class com.itsaky.androidide.utils.StopWatch** { *; }

