# Agent to Plugin Migration Design

**Date:** 2026-06-22
**Status:** Design Approved
**Approach:** Big Bang Migration

## Executive Summary

This design documents the migration of the Agent chat functionality from the main AndroidIDE app to the ai-assistant-plugin. This architectural change achieves:

- **Modularity**: All AI capabilities become plugin-based
- **Size Reduction**: ~50MB reduction in main app size
- **Code Deduplication**: Single llama.cpp implementation (in ai-core-plugin)
- **Flexibility**: Users can choose which AI features to install

## 1. Architecture Overview

### Current State

```
Main App (com.itsaky.androidide)
├─ Agent Tab (built-in bottom sheet tab)
│  ├─ ChatFragment, ChatViewModel, AgentFragmentContainer
│  ├─ GeminiRepositoryImpl (cloud API)
│  ├─ LocalLlmRepositoryImpl (local inference)
│  │  └─ LlmInferenceEngine (loads llama.aar dynamically)
│  └─ Tools, prompts, agentic runners
├─ llama.aar in assets/dynamic_libs/
└─ Dynamic library loader

ai-core-plugin
├─ LlmInferenceService implementation
├─ LocalLlmBackend
└─ llama-impl (native libs)

ai-code-helper-plugin
└─ Context menu items (Explain Code, Generate Code)
```

### New Architecture

```
Main App (com.itsaky.androidide)
└─ [Agent code removed]
    [llama.aar removed]
    [Dynamic library loader for llama removed]

ai-core-plugin (unchanged)
├─ LlmInferenceService implementation
├─ LocalLlmBackend
└─ llama-impl (native libs)

ai-assistant-plugin (renamed from ai-code-helper-plugin)
├─ Agent Tab (via UIExtension.getEditorTabs())
│  ├─ ChatFragment, ChatViewModel, AgentFragmentContainer
│  ├─ GeminiRepositoryImpl (cloud API)
│  ├─ PluginBasedLocalLlmRepository (consumes LlmInferenceService)
│  └─ Tools, prompts, agentic runners
└─ Context menu items (Explain Code, Generate Code)
```

### Key Principles

1. **Main app is AI-free**: No LLM code, no llama dependencies
2. **ai-core-plugin provides infrastructure**: LlmInferenceService with llama.cpp
3. **ai-assistant-plugin provides features**: Chat UI + code helpers
4. **Plugin dependency**: ai-assistant-plugin depends on ai-core-plugin for LOCAL_LLM
5. **Optional dependency**: Agent works with Gemini if ai-core not loaded
6. **Fallback behavior**: If ai-core missing, only GEMINI backend available

## 2. Component Migration Map

### Files Moving from `app/` to `ai-assistant-plugin/`

**Agent UI Layer:**
- `app/src/main/java/com/itsaky/androidide/agent/fragments/`
  - `AgentFragmentContainer.kt`
  - `ChatFragment.kt`
  - `ChatHistoryFragment.kt`
  - `ContextSelectionFragment.kt`
  - `AiSettingsFragment.kt`
  - `DisclaimerDialogFragment.kt`
  - `EncryptedPrefs.kt`

**ViewModels & State:**
- `app/src/main/java/com/itsaky/androidide/agent/viewmodel/`
  - `ChatViewModel.kt`
  - `AiSettingsViewModel.kt`
  - `OrchestratorAgent.kt`
  - `ExecutorAgent.kt`

**Repository Layer:**
- `app/src/main/java/com/itsaky/androidide/agent/repository/`
  - `GeminiRepository.kt` (interface)
  - `GeminiRepositoryImpl.kt`
  - `GeminiClient.kt`
  - `SwitchableGeminiRepository.kt`
  - `AiBackend.kt`
  - `AgenticRunner.kt`
  - `Planner.kt`, `Executor.kt`, `Critic.kt`
  - **NEW**: `PluginBasedLocalLlmRepository.kt` (replaces LocalLlmRepositoryImpl)

**Tools & Data:**
- `app/src/main/java/com/itsaky/androidide/agent/tool/` → plugin
- `app/src/main/java/com/itsaky/androidide/agent/data/` → plugin
- `app/src/main/java/com/itsaky/androidide/agent/model/` → plugin
- `app/src/main/java/com/itsaky/androidide/agent/actions/` → plugin

**UI Resources:**
- `app/src/main/res/layout/fragment_agent_*.xml` → plugin
- `app/src/main/res/navigation/agent_navigation.xml` → plugin
- Agent-specific strings, styles, drawables → plugin

### Files REMOVED from app (not moved)

- `LocalLlmRepositoryImpl.kt` - replaced by PluginBasedLocalLlmRepository
- `LlmInferenceEngine.kt` - no longer needed
- `LlmInferenceEngineProvider.kt` - no longer needed
- `ModelFamily.kt` - llama.cpp model detection logic
- `app/src/main/assets/dynamic_libs/llama.aar` - native libs now in ai-core-plugin
- `app/src/main/java/com/itsaky/androidide/utils/DynamicLibraryLoader.kt` - llama loading logic (if only used for llama)

### Files STAYING in app

- `EditorBottomSheetTabAdapter.kt` - already has plugin tab support
- Plugin manager infrastructure
- General IDE features

## 3. Plugin Service Integration

### PluginBasedLocalLlmRepository

New class that replaces `LocalLlmRepositoryImpl` and bridges the Agent's repository interface with the plugin service:

**Key Responsibilities:**
- Obtain `LlmInferenceService` via `PluginContext.services.get()`
- Translate between Agent's `ChatMessage` and plugin's `LlmInferenceService.ChatMessage`
- Handle streaming via plugin's `StreamCallback`
- Gracefully handle service unavailability

**Implementation Pattern:**

```kotlin
class PluginBasedLocalLlmRepository(
    private val context: PluginContext
) : GeminiRepository {

    private val llmService: LlmInferenceService? =
        context.services.get(LlmInferenceService::class.java)

    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ) {
        if (llmService == null) {
            onStateUpdate?.invoke(AgentState.Error("LLM service not available"))
            return
        }

        // Convert Agent's ChatMessage to plugin's ChatMessage format
        val pluginHistory = history.map { convertToPluginMessage(it) }

        // Use plugin's LlmInferenceService
        val config = LlmInferenceService.LlmConfig("local").apply {
            temperature = 0.7f
            maxTokens = 2048
        }

        val response = llmService.generateWithHistory(
            pluginHistory,
            prompt,
            config
        ).await()

        if (response.success) {
            addMessage(response.text, Sender.AGENT)
        } else {
            onStateUpdate?.invoke(AgentState.Error(response.error))
        }
    }

    private fun convertToPluginMessage(msg: ChatMessage): LlmInferenceService.ChatMessage {
        val role = when (msg.sender) {
            Sender.USER -> LlmInferenceService.ChatMessage.Role.USER
            Sender.AGENT -> LlmInferenceService.ChatMessage.Role.ASSISTANT
            Sender.SYSTEM -> LlmInferenceService.ChatMessage.Role.SYSTEM
        }
        return LlmInferenceService.ChatMessage(role, msg.text)
    }
}
```

### Backend Availability Check

In `ChatViewModel`, check plugin service availability:

```kotlin
private suspend fun getOrCreateRepository(context: Context): GeminiRepository? {
    val backendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)

    return when (AiBackend.valueOf(backendName)) {
        AiBackend.GEMINI -> GeminiRepositoryImpl(...)
        AiBackend.LOCAL_LLM -> {
            // Check if ai-core-plugin is loaded
            val service = pluginContext.services.get(LlmInferenceService::class.java)
            if (service == null) {
                logger.warn("AI Core plugin not loaded, LOCAL_LLM unavailable")
                null
            } else {
                PluginBasedLocalLlmRepository(pluginContext)
            }
        }
    }
}
```

### Settings UI Update

In `AiSettingsFragment`, dynamically show/hide LOCAL_LLM backend option:

```kotlin
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val llmService = pluginContext.services.get(LlmInferenceService::class.java)

    val availableBackends = if (llmService != null) {
        listOf(AiBackend.GEMINI, AiBackend.LOCAL_LLM)
    } else {
        listOf(AiBackend.GEMINI) // Only show Gemini if plugin unavailable
    }

    backendSpinner.adapter = BackendAdapter(availableBackends)
}
```

## 4. Plugin Configuration & Metadata

### ai-assistant-plugin Manifest

`ai-assistant-plugin/src/main/AndroidManifest.xml`:

```xml
<application
    android:label="AI Assistant"
    android:theme="@style/PluginTheme">

    <meta-data
        android:name="plugin.id"
        android:value="com.itsaky.androidide.plugins.aiassistant" />

    <meta-data
        android:name="plugin.name"
        android:value="AI Assistant" />

    <meta-data
        android:name="plugin.version"
        android:value="${pluginVersion}" />

    <meta-data
        android:name="plugin.description"
        android:value="AI-powered chat assistant and code helpers with local and cloud LLM support" />

    <meta-data
        android:name="plugin.author"
        android:value="AndroidIDE" />

    <meta-data
        android:name="plugin.min_ide_version"
        android:value="1.0.0" />

    <meta-data
        android:name="plugin.permissions"
        android:value="network.access" />

    <meta-data
        android:name="plugin.dependencies"
        android:value="com.itsaky.androidide.plugins.aicore" />

    <meta-data
        android:name="plugin.main_class"
        android:value="com.itsaky.androidide.plugins.aiassistant.AiAssistantPlugin" />
</application>
```

**Key Changes:**
- `plugin.id`: `com.itsaky.androidide.plugins.aiassistant`
- `plugin.name`: "AI Assistant"
- `plugin.permissions`: `network.access` (for Gemini API)
- `plugin.dependencies`: `com.itsaky.androidide.plugins.aicore`

### Build Configuration

`ai-assistant-plugin/build.gradle.kts`:

```kotlin
android {
    namespace = "com.itsaky.androidide.plugins.aiassistant"

    defaultConfig {
        minSdk = 33
        buildConfigField("String", "PLUGIN_VERSION", "\"2.0.0\"")
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    compileOnly(project(":plugin-api"))

    // Agent dependencies
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation(libs.google.genai)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation(libs.tooling.slf4j)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
```

**Removed Dependencies:**
- No `llama-impl` dependency
- No `llama-api` dependency
- No native library dependencies

### Plugin Class

`ai-assistant-plugin/.../AiAssistantPlugin.kt`:

```kotlin
class AiAssistantPlugin : IPlugin, UIExtension {

    private lateinit var context: PluginContext
    private var llmService: LlmInferenceService? = null

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        context.logger.info("AI Assistant Plugin initializing...")
        return true
    }

    override fun activate(): Boolean {
        // Get LLM service from ai-core plugin (may be null if plugin not loaded)
        llmService = context.services.get(LlmInferenceService::class.java)

        if (llmService == null) {
            context.logger.warn("LlmInferenceService not available - LOCAL_LLM backend disabled")
            context.logger.warn("Install AI Core plugin to enable local LLM support")
        } else {
            context.logger.info("LlmInferenceService available - both backends enabled")
        }

        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("AI Assistant Plugin deactivating...")
        return true
    }

    override fun dispose() {
        context.logger.info("AI Assistant Plugin disposing...")
    }

    // Register Agent tab
    override fun getEditorTabs(): List<TabItem> {
        return listOf(
            TabItem(
                id = "agent_chat",
                title = "Agent",
                order = 100,
                fragmentFactory = { ChatFragment() },
                isEnabled = true,
                isVisible = true,
                tooltipTag = "agent_chat_tab"
            )
        )
    }

    // Keep existing context menu items
    override fun getContextMenuItems(menuContext: ContextMenuContext): List<MenuItem> {
        val selectedText = menuContext.selectedText
        if (selectedText.isNullOrBlank()) {
            return emptyList()
        }

        return listOf(
            MenuItem(
                id = "ai_explain_code",
                title = "Explain Code",
                isEnabled = true,
                isVisible = true,
                action = { explainCode(selectedText) }
            ),
            MenuItem(
                id = "ai_generate_code",
                title = "Generate Code",
                isEnabled = true,
                isVisible = true,
                action = { generateCode(selectedText) }
            )
        )
    }

    override fun getMainMenuItems(): List<MenuItem> = emptyList()
    override fun getSideMenuItems(): List<NavigationItem> = emptyList()
}
```

## 5. Main App Cleanup

### Code Removal

**Delete entire agent package:**
```bash
rm -rf app/src/main/java/com/itsaky/androidide/agent/
```

**Remove llama assets:**
```bash
rm -rf app/src/main/assets/dynamic_libs/llama*.aar
rm -rf app/src/v8Debug/assets/dynamic_libs/llama-v8.aar
rm -rf app/libs/v7/llama*.aar
rm -rf app/libs/v8/llama*.aar
```

**Dynamic library loader cleanup:**
- Remove `getLlamaClassLoader()` from `DynamicLibraryLoader.kt`
- Delete file if only used for llama

**Assets installer cleanup:**
- Remove llama-specific logic from `SplitAssetsInstaller.kt`
- Remove llama-specific logic from `BundledAssetsInstaller.kt`
- Remove llama-specific logic from `AssetsInstallationHelper.kt`

### Build Configuration Updates

`app/build.gradle.kts`:

```kotlin
dependencies {
    // REMOVE these lines:
    // implementation(project(":llama-api"))
    // implementation(project(":llama-impl"))

    // Keep everything else
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.genai)
    // ... other dependencies
}
```

### Bottom Sheet Tab Adapter

`app/src/main/java/com/itsaky/androidide/adapters/EditorBottomSheetTabAdapter.kt`:

Remove the built-in Agent tab registration:

```kotlin
// DELETE this block:
/*
if (FeatureFlags.isExperimentsEnabled) {
    add(Tab(
        title = getString(R.string.title_agent),
        fragmentClass = AgentFragmentContainer::class.java,
        itemId = TAB_AGENT,
        tooltipTag = TooltipTag.PROJECT_AGENT,
    ))
}
*/

// Keep plugin tab registration logic (already exists)
```

Remove constant:
```kotlin
// DELETE: const val TAB_AGENT = 7
```

### Resource Cleanup

```bash
# Remove layouts
rm app/src/main/res/layout/fragment_agent_*.xml
rm app/src/main/res/layout/fragment_chat*.xml

# Remove navigation
rm app/src/main/res/navigation/agent_navigation.xml

# Edit strings.xml - remove Agent-specific strings
```

## 6. Error Handling & Fallback Behavior

### Plugin Dependency Scenarios

**Scenario 1: AI Core Plugin Not Installed**
```kotlin
override fun activate(): Boolean {
    llmService = context.services.get(LlmInferenceService::class.java)

    if (llmService == null) {
        context.logger.warn("AI Core plugin not found - LOCAL_LLM backend unavailable")
        // Plugin still activates successfully
        // UI shows only GEMINI backend option
    }
    return true
}
```

**Scenario 2: User Selects LOCAL_LLM Without Plugin**
```kotlin
fun saveBackend(backend: AiBackend) {
    if (backend == AiBackend.LOCAL_LLM) {
        val service = context.services.get(LlmInferenceService::class.java)
        if (service == null) {
            showError(
                title = "Plugin Required",
                message = "LOCAL_LLM requires AI Core plugin. Please install it from Plugin Manager.",
                action = "Open Plugin Manager"
            )
            return
        }
    }
    prefs.putString(PREF_KEY_AI_BACKEND, backend.name)
}
```

### Graceful Degradation Flow

```
User opens Agent tab (plugin loaded)
    ↓
Check backend preference
    ↓
If GEMINI → Works (no dependency)
    ↓
If LOCAL_LLM:
    ├─ ai-core-plugin loaded? → Works
    ├─ ai-core-plugin missing? → Show message, switch to GEMINI
    └─ ai-core-plugin error? → Show message, switch to GEMINI
```

### Error Messages

**Service Unavailable:**
```
"Local LLM service unavailable. Using Gemini API instead."
```

**Plugin Not Loaded:**
```
"AI Core plugin not loaded. Install it from Settings → Plugin Manager."
```

**Plugin Installation Message in Chat UI:**
```
┌─────────────────────────────────────┐
│  ⚠️  Local LLM Not Available        │
│                                     │
│  To use local AI models, install   │
│  the AI Core plugin:                │
│                                     │
│  Settings → Plugin Manager →        │
│  Install AI Core                    │
│                                     │
│  Currently using Gemini API.        │
└─────────────────────────────────────┘
```

## 7. Testing Strategy

### Unit Tests

**PluginBasedLocalLlmRepository Tests:**
- Test with service available (successful generation)
- Test with service unavailable (error state)
- Test message format conversion
- Test streaming callback handling

**Plugin Activation Tests:**
- Plugin activates with ai-core loaded
- Plugin activates without ai-core (logs warning)
- getEditorTabs returns Agent tab
- getContextMenuItems returns code helpers

### Integration Tests

**End-to-End Agent Flow:**
1. Install both plugins
2. Verify plugins loaded
3. Open Agent tab
4. Select LOCAL_LLM backend
5. Send message
6. Verify response

**Fallback to Gemini:**
1. Install only ai-assistant-plugin
2. Open Agent settings
3. Verify LOCAL_LLM option hidden
4. Verify GEMINI backend works

### Manual Testing Checklist

**Test Case 1: Fresh Install**
- [ ] Install ai-core-plugin
- [ ] Install ai-assistant-plugin
- [ ] Verify Agent tab appears
- [ ] Verify both backends available
- [ ] Test GEMINI backend
- [ ] Test LOCAL_LLM backend
- [ ] Test context menu items

**Test Case 2: Plugin Dependency Missing**
- [ ] Install only ai-assistant-plugin
- [ ] Verify Agent tab appears
- [ ] Verify only GEMINI backend shown
- [ ] Test GEMINI works
- [ ] Verify LOCAL_LLM message shown

**Test Case 3: Upgrade Path**
- [ ] Start with built-in Agent
- [ ] Install plugins
- [ ] Restart app
- [ ] Verify Agent from plugin
- [ ] Verify history preserved
- [ ] Verify settings migrated

**Test Case 4: Plugin Uninstall**
- [ ] Uninstall ai-assistant-plugin
- [ ] Verify Agent tab disappears
- [ ] Verify no crashes
- [ ] Reinstall plugin
- [ ] Verify Agent reappears

## 8. Data Migration & Backward Compatibility

### Chat History Migration

**Storage locations:**
```
OLD: /data/data/com.itsaky.androidide/files/chat_sessions/
NEW: /data/data/com.itsaky.androidide/files/plugins/
     com.itsaky.androidide.plugins.aiassistant/chat_sessions/
```

**Migration in plugin activation:**

```kotlin
override fun activate(): Boolean {
    migrateDataIfNeeded()
    return true
}

private fun migrateDataIfNeeded() {
    val appChatDir = File(context.getAppFilesDir(), "chat_sessions")
    val pluginChatDir = File(context.getPluginFilesDir(), "chat_sessions")

    if (appChatDir.exists() && !pluginChatDir.exists()) {
        context.logger.info("Migrating chat history from app to plugin storage")
        pluginChatDir.mkdirs()
        appChatDir.listFiles()?.forEach { file ->
            file.copyTo(File(pluginChatDir, file.name), overwrite = false)
        }
        // Keep original files (don't delete)
    }
}
```

### SharedPreferences Migration

**Settings to migrate:**
- `ai_backend_preference`
- `local_llm_model_path`
- `local_llm_model_sha256`
- `gemini_api_key` (encrypted)

```kotlin
private fun migrateSettings() {
    val appPrefs = context.getAppSharedPreferences("LlamaPrefs")
    val pluginPrefs = context.getPluginSharedPreferences("AgentSettings")

    if (!pluginPrefs.contains(PREF_KEY_AI_BACKEND)) {
        val backend = appPrefs.getString(PREF_KEY_AI_BACKEND, null)
        if (backend != null) {
            pluginPrefs.edit {
                putString(PREF_KEY_AI_BACKEND, backend)
            }
        }
    }

    migrateIfNeeded(appPrefs, pluginPrefs, PREF_KEY_LOCAL_MODEL_PATH)
    migrateIfNeeded(appPrefs, pluginPrefs, PREF_KEY_LOCAL_MODEL_SHA256)
    migrateEncryptedKey(appPrefs, pluginPrefs)
}
```

### Version Compatibility Matrix

| App Version | ai-core | ai-assistant | Agent Tab Source |
|-------------|---------|--------------|------------------|
| Old         | -       | -            | Built-in         |
| New         | -       | Not installed| Hidden           |
| New         | v1.0    | Not installed| Hidden           |
| New         | -       | v1.0         | Plugin (Gemini)  |
| New         | v1.0    | v1.0         | Plugin (Both)    |

### Breaking Changes

None for users who install plugins. Agent tab simply moves from app to plugin.

## 9. Build & Deployment

### Module Renaming

```bash
# Rename plugin directory
mv ai-code-helper-plugin/ ai-assistant-plugin/

# Update settings.gradle.kts
include(":ai-assistant-plugin")
```

### Build Output

After migration:
```
build/outputs/
├── app/
│   └── apk/v8/debug/
│       └── CodeOnTheGo-v8-debug.apk  (~150MB, was ~200MB)
│
├── ai-core-plugin/
│   └── apk/v8/debug/
│       └── ai-core-plugin-v8-debug.apk  (~60MB)
│
└── ai-assistant-plugin/
    └── apk/v7/debug/
        └── ai-assistant-plugin-v7-debug.apk  (~5MB)
```

**App size reduction: ~50MB** (llama.aar removed)

### Deployment Steps

**1. Build all artifacts:**
```bash
./gradlew clean
./gradlew :app:assembleDebug
./gradlew :ai-core-plugin:assembleDebug
./gradlew :ai-assistant-plugin:assembleDebug
```

**2. Package plugins:**
```bash
cp ai-core-plugin/build/outputs/apk/v8/debug/*.apk ai-core-plugin.cgp
cp ai-assistant-plugin/build/outputs/apk/v7/debug/*.apk ai-assistant-plugin.cgp
```

**3. Test installation:**
```bash
adb install -r app/build/outputs/apk/v8/debug/CodeOnTheGo-v8-debug.apk
adb push ai-core-plugin.cgp /sdcard/Download/
adb push ai-assistant-plugin.cgp /sdcard/Download/
```

### User Migration Path

**For existing users:**

1. **App Update**: Agent tab disappears, message shown
2. **Plugin Install**: User installs both plugins from Plugin Manager
3. **Automatic Migration**: Chat history and settings migrated on first launch
4. **Agent Restored**: Tab reappears with full functionality

### Documentation Updates

**Files to update:**
- `README.md` - architecture diagram, plugin installation
- `docs/plugins/ai-core-plugin.md` - existing
- `docs/plugins/ai-assistant-plugin.md` - NEW
- `docs/migration/agent-to-plugin-migration.md` - NEW

## 10. Implementation Checklist

### Phase 1: Preparation
- [ ] Rename `ai-code-helper-plugin` to `ai-assistant-plugin`
- [ ] Update plugin manifest (id, name, description, dependencies)
- [ ] Update build.gradle.kts (namespace, dependencies)

### Phase 2: Code Migration
- [ ] Copy Agent fragments to plugin
- [ ] Copy Agent viewmodels to plugin
- [ ] Copy Agent repositories to plugin (except LocalLlmRepositoryImpl)
- [ ] Copy Agent tools, data, models to plugin
- [ ] Copy Agent UI resources to plugin

### Phase 3: Service Integration
- [ ] Implement PluginBasedLocalLlmRepository
- [ ] Update ChatViewModel to use plugin service
- [ ] Update AiSettingsFragment for dynamic backend list
- [ ] Update AiAssistantPlugin.getEditorTabs()

### Phase 4: Main App Cleanup
- [ ] Remove Agent package from app
- [ ] Remove llama.aar from assets
- [ ] Remove llama libs from app/libs
- [ ] Remove DynamicLibraryLoader llama methods
- [ ] Remove llama asset installation logic
- [ ] Remove Agent tab from EditorBottomSheetTabAdapter
- [ ] Remove Agent resources from app/res
- [ ] Update app build.gradle.kts

### Phase 5: Migration Logic
- [ ] Implement chat history migration
- [ ] Implement settings migration
- [ ] Implement encrypted key migration

### Phase 6: Testing
- [ ] Write unit tests for PluginBasedLocalLlmRepository
- [ ] Write unit tests for plugin activation
- [ ] Write integration tests for end-to-end flow
- [ ] Manual testing checklist execution

### Phase 7: Documentation
- [ ] Update README.md
- [ ] Create ai-assistant-plugin.md
- [ ] Create migration guide
- [ ] Update architecture diagrams

### Phase 8: Validation
- [ ] Build all artifacts
- [ ] Test fresh install
- [ ] Test upgrade path
- [ ] Test plugin dependency scenarios
- [ ] Test data migration
- [ ] Verify app size reduction

## Success Criteria

1. ✅ Main app has no Agent code or llama dependencies
2. ✅ Main app size reduced by ~50MB
3. ✅ Agent tab provided by ai-assistant-plugin
4. ✅ Both GEMINI and LOCAL_LLM backends work
5. ✅ LOCAL_LLM uses ai-core-plugin's LlmInferenceService
6. ✅ Graceful fallback when ai-core-plugin missing
7. ✅ Chat history and settings migrate automatically
8. ✅ Context menu items work
9. ✅ All tests pass
10. ✅ Documentation updated

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Data loss during migration | High | Keep original files, don't delete |
| Plugin load order issues | Medium | Use plugin dependencies mechanism |
| Encrypted key migration fails | Medium | Fallback to manual re-entry |
| Service unavailable crashes | Medium | Null checks, error states |
| User confusion about plugins | Low | Clear messaging, documentation |

## Timeline Estimate

- **Code Migration**: 2-3 hours
- **Service Integration**: 2 hours
- **Testing**: 2-3 hours
- **Documentation**: 1 hour
- **Total**: ~8 hours

This is a Big Bang migration, all changes committed together.
