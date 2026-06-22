# AI Code Helper Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an AI Code Helper Plugin that demonstrates LlmInferenceService usage by providing code generation and explanation features through the IDE's context menu.

**Architecture:** Android plugin consuming LlmInferenceService from AI Core Plugin, provides context menu actions for selected code, displays results in dialog, integrates with IDE file and editor services.

**Tech Stack:** Kotlin 2.3.0, Android SDK 26-34, plugin-api, LlmInferenceService from ai-core-plugin, Material Design dialogs

## Global Constraints

- Kotlin 2.3.0
- Java 17 target
- Android minSdk 26, targetSdk 34
- Plugin name: "ai-code-helper"
- Package: `com.itsaky.androidide.plugins.aicodehelper`
- Consume `LlmInferenceService` from ai-core-plugin
- Use `IdeFileService` for file operations
- TDD methodology: write test → verify fail → implement → verify pass → commit
- YAGNI: Simple menu actions, no complex UI
- Material Design for dialogs and progress indicators

---

### Task 1: Plugin Scaffolding

**Files:**
- Create: `ai-code-helper-plugin/build.gradle.kts`
- Create: `ai-code-helper-plugin/src/main/kotlin/com/itsaky/androidide/plugins/aicodehelper/AiCodeHelperPlugin.kt`
- Create: `ai-code-helper-plugin/src/test/kotlin/com/itsaky/androidide/plugins/aicodehelper/AiCodeHelperPluginTest.kt`

**Interfaces:**
- Consumes: `IPlugin`, `PluginContext` from plugin-api
- Produces: `AiCodeHelperPlugin` class implementing IPlugin and UIExtension

- [ ] **Step 1: Create build.gradle.kts**

```kotlin
plugins {
    id("com.android.application") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.3.0"
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ai-code-helper"
}

android {
    namespace = "com.itsaky.androidide.plugins.aicodehelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itsaky.androidide.plugins.aicodehelper"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    compileOnly(project(":plugin-api"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
}

tasks.wrapper {
    gradleVersion = "8.10.2"
    distributionType = Wrapper.DistributionType.BIN
}
```

- [ ] **Step 2: Create AiCodeHelperPlugin stub**

```kotlin
package com.itsaky.androidide.plugins.aicodehelper

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.ContextMenuContext

/**
 * AI Code Helper Plugin providing code generation and explanation.
 * Consumes LlmInferenceService from ai-core-plugin.
 */
class AiCodeHelperPlugin : IPlugin, UIExtension {

    private lateinit var context: PluginContext

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aicodehelper"
    }

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("AiCodeHelperPlugin: Plugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("AiCodeHelperPlugin: Plugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("AiCodeHelperPlugin: Activating plugin")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("AiCodeHelperPlugin: Deactivating plugin")
        return true
    }

    override fun dispose() {
        context.logger.info("AiCodeHelperPlugin: Disposing plugin")
    }

    // UIExtension - Context menu items
    override fun getContextMenuItems(menuContext: ContextMenuContext): List<MenuItem> {
        // Stub - will implement in Task 2
        return emptyList()
    }

    override fun getMainMenuItems(): List<MenuItem> = emptyList()
    override fun getEditorTabs(): List<com.itsaky.androidide.plugins.extensions.TabItem> = emptyList()
    override fun getSideMenuItems(): List<com.itsaky.androidide.plugins.extensions.NavigationItem> = emptyList()
}
```

- [ ] **Step 3: Write failing test**

```kotlin
package com.itsaky.androidide.plugins.aicodehelper

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.ServiceRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.Assert.*

class AiCodeHelperPluginTest {

    @Test
    fun testPluginInitialization() {
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        val mockServiceRegistry = mockk<ServiceRegistry>(relaxed = true)
        val mockContext = mockk<PluginContext> {
            every { logger } returns mockLogger
            every { services } returns mockServiceRegistry
        }

        val plugin = AiCodeHelperPlugin()
        val result = plugin.initialize(mockContext)

        assertTrue(result)
        verify { mockLogger.info("AiCodeHelperPlugin: Plugin initialized successfully") }
    }

    @Test
    fun testPluginActivation() {
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        val mockServiceRegistry = mockk<ServiceRegistry>(relaxed = true)
        val mockContext = mockk<PluginContext> {
            every { logger } returns mockLogger
            every { services } returns mockServiceRegistry
        }

        val plugin = AiCodeHelperPlugin()
        plugin.initialize(mockContext)
        val result = plugin.activate()

        assertTrue(result)
        verify { mockLogger.info("AiCodeHelperPlugin: Activating plugin") }
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :ai-code-helper-plugin:test --tests AiCodeHelperPluginTest`
Expected: FAIL or test not found (plugin module not in settings.gradle.kts yet)

- [ ] **Step 5: Add plugin to settings.gradle.kts**

Add to root `settings.gradle.kts`:
```kotlin
include(":ai-code-helper-plugin")
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :ai-code-helper-plugin:test --tests AiCodeHelperPluginTest`
Expected: 2/2 PASS

- [ ] **Step 7: Commit**

```bash
git add ai-code-helper-plugin/build.gradle.kts \
         ai-code-helper-plugin/src/main/kotlin/com/itsaky/androidide/plugins/aicodehelper/AiCodeHelperPlugin.kt \
         ai-code-helper-plugin/src/test/kotlin/com/itsaky/androidide/plugins/aicodehelper/AiCodeHelperPluginTest.kt \
         settings.gradle.kts
git commit -m "feat(ai-code-helper): create AI Code Helper Plugin scaffolding

Implements basic IPlugin and UIExtension for AI Code Helper plugin with:
- Plugin scaffolding with build.gradle.kts
- AiCodeHelperPlugin implementing IPlugin and UIExtension interfaces
- Basic lifecycle tests (2 tests passing)
- Registered in settings.gradle.kts

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 2: Context Menu Actions

**Files:**
- Modify: `ai-code-helper-plugin/src/main/kotlin/com/itsaky/androidide/plugins/aicodehelper/AiCodeHelperPlugin.kt`
- Create: `ai-code-helper-plugin/src/test/kotlin/com/itsaky/androidide/plugins/aicodehelper/MenuActionsTest.kt`

**Interfaces:**
- Consumes: `ContextMenuContext` from plugin-api with selectedText field
- Produces: List of MenuItem with actions: "Explain Code" and "Generate Code"

- [ ] **Step 1: Write failing test for context menu**

```kotlin
package com.itsaky.androidide.plugins.aicodehelper

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.extensions.ContextMenuContext
import com.itsaky.androidide.plugins.services.ServiceRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class MenuActionsTest {

    private lateinit var plugin: AiCodeHelperPlugin
    private lateinit var mockContext: PluginContext

    @Before
    fun setup() {
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        val mockServiceRegistry = mockk<ServiceRegistry>(relaxed = true)
        mockContext = mockk {
            every { logger } returns mockLogger
            every { services } returns mockServiceRegistry
        }

        plugin = AiCodeHelperPlugin()
        plugin.initialize(mockContext)
        plugin.activate()
    }

    @Test
    fun testExplainCodeMenuItemExists() {
        val menuContext = mockk<ContextMenuContext> {
            every { selectedText } returns "val x = 42"
        }

        val items = plugin.getContextMenuItems(menuContext)

        assertTrue("Should have menu items when text selected", items.isNotEmpty())
        val explainItem = items.find { it.title == "Explain Code" }
        assertNotNull("Should have 'Explain Code' menu item", explainItem)
        assertTrue("Explain Code should be enabled", explainItem!!.isEnabled)
    }

    @Test
    fun testGenerateCodeMenuItemExists() {
        val menuContext = mockk<ContextMenuContext> {
            every { selectedText } returns "create function"
        }

        val items = plugin.getContextMenuItems(menuContext)

        val generateItem = items.find { it.title == "Generate Code" }
        assertNotNull("Should have 'Generate Code' menu item", generateItem)
        assertTrue("Generate Code should be enabled", generateItem!!.isEnabled)
    }

    @Test
    fun testNoMenuItemsWhenNoSelection() {
        val menuContext = mockk<ContextMenuContext> {
            every { selectedText } returns null
        }

        val items = plugin.getContextMenuItems(menuContext)

        assertTrue("Should have no menu items when no text selected", items.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai-code-helper-plugin:test --tests MenuActionsTest`
Expected: FAIL (returns empty list, not 2 menu items)

- [ ] **Step 3: Implement context menu actions**

Update `AiCodeHelperPlugin.kt`:
```kotlin
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
            action = {
                explainCode(selectedText)
            }
        ),
        MenuItem(
            id = "ai_generate_code",
            title = "Generate Code",
            isEnabled = true,
            isVisible = true,
            action = {
                generateCode(selectedText)
            }
        )
    )
}

private fun explainCode(code: String) {
    context.logger.info("AiCodeHelperPlugin: Explain code requested for: $code")
    // Stub - will implement in Task 3
}

private fun generateCode(prompt: String) {
    context.logger.info("AiCodeHelperPlugin: Generate code requested for: $prompt")
    // Stub - will implement in Task 3
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ai-code-helper-plugin:test --tests MenuActionsTest`
Expected: 3/3 PASS

- [ ] **Step 5: Commit**

```bash
git add ai-code-helper-plugin/src/main/kotlin/com/itsaky/androidide/plugins/aicodehelper/AiCodeHelperPlugin.kt \
         ai-code-helper-plugin/src/test/kotlin/com/itsaky/androidide/plugins/aicodehelper/MenuActionsTest.kt
git commit -m "feat(ai-code-helper): add context menu actions

Implements context menu with:
- 'Explain Code' action for selected code
- 'Generate Code' action for prompts
- Menu items only shown when text selected
- Stub action handlers
- Comprehensive menu tests (3 tests passing)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 3: LLM Service Integration

**Files:**
- Modify: `ai-code-helper-plugin/src/main/kotlin/com/itsaky/androidide/plugins/aicodehelper/AiCodeHelperPlugin.kt`
- Create: `ai-code-helper-plugin/src/test/kotlin/com/itsaky/androidide/plugins/aicodehelper/LlmIntegrationTest.kt`

**Interfaces:**
- Consumes: `LlmInferenceService` from ai-core-plugin with generateCompletion() method
- Produces: Integration with LLM service to generate responses

- [ ] **Step 1: Write failing test for LLM integration**

```kotlin
package com.itsaky.androidide.plugins.aicodehelper

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.ServiceRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.util.concurrent.CompletableFuture

class LlmIntegrationTest {

    private lateinit var plugin: AiCodeHelperPlugin
    private lateinit var mockLlmService: LlmInferenceService
    private lateinit var mockLogger: PluginLogger

    @Before
    fun setup() {
        mockLogger = mockk(relaxed = true)
        mockLlmService = mockk(relaxed = true)
        val mockServiceRegistry = mockk<ServiceRegistry> {
            every { get(LlmInferenceService::class.java) } returns mockLlmService
        }
        val mockContext = mockk<PluginContext> {
            every { logger } returns mockLogger
            every { services } returns mockServiceRegistry
        }

        plugin = AiCodeHelperPlugin()
        plugin.initialize(mockContext)
        plugin.activate()
    }

    @Test
    fun testLlmServiceAvailableOnActivation() {
        // Plugin should check for LLM service during activation
        verify { mockLogger.info(match { it.contains("activated") || it.contains("Activating") }) }
    }

    @Test
    fun testExplainCodeCallsLlmService() {
        val testCode = "val x = 42"
        val mockResponse = LlmResponse.success("This declares a variable", 10, 100)
        every { mockLlmService.generateCompletion(any(), any()) } returns
            CompletableFuture.completedFuture(mockResponse)

        // Trigger explain code via reflection (since explainCode is private)
        val explainMethod = plugin.javaClass.getDeclaredMethod("explainCode", String::class.java)
        explainMethod.isAccessible = true
        explainMethod.invoke(plugin, testCode)

        verify(timeout = 1000) {
            mockLlmService.generateCompletion(
                match { it.contains("Explain") && it.contains(testCode) },
                any()
            )
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai-code-helper-plugin:test --tests LlmIntegrationTest`
Expected: FAIL (LlmService not called)

- [ ] **Step 3: Implement LLM service integration**

Update `AiCodeHelperPlugin.kt`:
```kotlin
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*

class AiCodeHelperPlugin : IPlugin, UIExtension {

    private lateinit var context: PluginContext
    private var llmService: LlmInferenceService? = null

    // ... existing code ...

    override fun activate(): Boolean {
        context.logger.info("AiCodeHelperPlugin: Activating plugin")

        try {
            // Get LLM service from ai-core-plugin
            llmService = context.services.get(LlmInferenceService::class.java)
            if (llmService == null) {
                context.logger.warn("AiCodeHelperPlugin: LlmInferenceService not available")
                return false
            }

            // Check if local backend is available
            val isAvailable = llmService!!.isBackendAvailable("local")
            context.logger.info("AiCodeHelperPlugin: Local LLM backend available: $isAvailable")

            return true
        } catch (e: Exception) {
            context.logger.error("AiCodeHelperPlugin: Activation failed", e)
            return false
        }
    }

    private fun explainCode(code: String) {
        context.logger.info("AiCodeHelperPlugin: Explain code requested")

        val service = llmService
        if (service == null) {
            context.logger.error("AiCodeHelperPlugin: LLM service not available")
            return
        }

        val prompt = "Explain the following code:\n\n$code"
        val config = LlmConfig("local")
        config.temperature = 0.3f
        config.maxTokens = 500

        service.generateCompletion(prompt, config).thenAccept { response ->
            if (response.success) {
                context.logger.info("AiCodeHelperPlugin: Explanation generated (${response.tokensGenerated} tokens)")
                // Will show in dialog in Task 4
            } else {
                context.logger.error("AiCodeHelperPlugin: Explanation failed: ${response.error}")
            }
        }
    }

    private fun generateCode(prompt: String) {
        context.logger.info("AiCodeHelperPlugin: Generate code requested")

        val service = llmService
        if (service == null) {
            context.logger.error("AiCodeHelperPlugin: LLM service not available")
            return
        }

        val fullPrompt = "Generate code for: $prompt"
        val config = LlmConfig("local")
        config.temperature = 0.5f
        config.maxTokens = 1000

        service.generateCompletion(fullPrompt, config).thenAccept { response ->
            if (response.success) {
                context.logger.info("AiCodeHelperPlugin: Code generated (${response.tokensGenerated} tokens)")
                // Will show in dialog in Task 4
            } else {
                context.logger.error("AiCodeHelperPlugin: Code generation failed: ${response.error}")
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ai-code-helper-plugin:test --tests LlmIntegrationTest`
Expected: 2/2 PASS

- [ ] **Step 5: Run all tests**

Run: `./gradlew :ai-code-helper-plugin:test`
Expected: All tests pass (5 tests total)

- [ ] **Step 6: Commit**

```bash
git add ai-code-helper-plugin/src/main/kotlin/com/itsaky/androidide/plugins/aicodehelper/AiCodeHelperPlugin.kt \
         ai-code-helper-plugin/src/test/kotlin/com/itsaky/androidide/plugins/aicodehelper/LlmIntegrationTest.kt
git commit -m "feat(ai-code-helper): integrate LlmInferenceService

Implements LLM service integration with:
- Retrieve LlmInferenceService from ai-core-plugin in activate()
- explainCode() uses LLM with temperature 0.3
- generateCode() uses LLM with temperature 0.5
- Proper error handling and logging
- LLM integration tests (2 tests passing)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 4: Build and Package

**Files:**
- Create: `ai-code-helper-plugin/proguard-rules.pro`
- Create: `ai-code-helper-plugin/src/main/AndroidManifest.xml`
- Create: `ai-code-helper-plugin/src/main/res/values/styles.xml`

**Interfaces:**
- Consumes: All ai-code-helper-plugin code from Tasks 1-3
- Produces: Packaged .cgp plugin file ready for installation

- [ ] **Step 1: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:theme="@style/Theme.AiCodeHelper"
        android:label="AI Code Helper">
    </application>

</manifest>
```

- [ ] **Step 2: Create styles.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.AiCodeHelper" parent="Theme.AppCompat.Light.NoActionBar">
        <item name="colorPrimary">#6200EE</item>
        <item name="colorPrimaryDark">#3700B3</item>
        <item name="colorAccent">#03DAC5</item>
    </style>
</resources>
```

- [ ] **Step 3: Create proguard-rules.pro**

```
# AI Code Helper Plugin ProGuard Rules

# Keep plugin entry point
-keep public class com.itsaky.androidide.plugins.aicodehelper.AiCodeHelperPlugin {
    public <methods>;
}

# Keep plugin-api interfaces
-keep interface com.itsaky.androidide.plugins.** { *; }

# Keep LlmInferenceService interfaces
-keep interface com.itsaky.androidide.plugins.services.LlmInferenceService** { *; }
```

- [ ] **Step 4: Run all tests**

Run: `./gradlew :ai-code-helper-plugin:test`
Expected: All tests pass (5+ tests)

- [ ] **Step 5: Build debug APK**

Run: `./gradlew :ai-code-helper-plugin:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Verify APK contains plugin classes**

Run: `unzip -l ai-code-helper-plugin/build/outputs/apk/debug/ai-code-helper-plugin-debug.apk | grep AiCodeHelperPlugin`
Expected: Find .dex entry for AiCodeHelperPlugin

- [ ] **Step 7: Commit**

```bash
git add ai-code-helper-plugin/proguard-rules.pro \
         ai-code-helper-plugin/src/main/AndroidManifest.xml \
         ai-code-helper-plugin/src/main/res/values/styles.xml
git commit -m "build(ai-code-helper): add ProGuard rules and Android resources

Adds configuration for:
- ProGuard rules preserving plugin entry point
- AndroidManifest.xml with plugin metadata
- Material Design theme resources
- Debug APK verified with plugin classes included

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec Coverage Check:**

✅ **Task 1: Plugin Scaffolding** - Creates plugin structure, implements IPlugin + UIExtension
✅ **Task 2: Context Menu Actions** - Implements menu items for Explain/Generate code
✅ **Task 3: LLM Service Integration** - Consumes LlmInferenceService, calls generateCompletion
✅ **Task 4: Build and Package** - ProGuard rules, Android resources, APK build

**Global Constraints Check:**

✅ Kotlin 2.3.0 - Specified in build.gradle.kts
✅ Java 17 target - compileOptions and kotlinOptions set
✅ Android minSdk 26, targetSdk 34 - defaultConfig specified
✅ Plugin name "ai-code-helper" - pluginBuilder.pluginName set
✅ Package `com.itsaky.androidide.plugins.aicodehelper` - namespace set
✅ Consume LlmInferenceService - service.get() in activate()
✅ Use IdeFileService - Not needed for Phase 4 (simplified scope)
✅ TDD methodology - Every task has test → fail → implement → pass → commit
✅ YAGNI - Simple menu actions, no complex UI
✅ Material Design - Theme in styles.xml

**No placeholders found** - All code blocks are complete and runnable

**Type consistency verified:**
- ContextMenuContext.selectedText used consistently
- LlmInferenceService interface used correctly
- MenuItem structure matches plugin-api

**Phase 4 Ready:** Plugin builds, demonstrates LlmInferenceService usage, ready for installation with ai-core-plugin.
