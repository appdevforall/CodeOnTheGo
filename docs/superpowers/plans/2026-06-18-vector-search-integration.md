# Vector Search Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate semantic code search from convert-plugin-to-library branch into stage branch, enabling developers to search code by meaning using local LLM embeddings.

**Architecture:** Copy complete vector-search module from convert-plugin-to-library branch, wire it to EditorViewModel through new VectorSearchCommand, implement on-demand indexing that triggers on first search and persists to SQLite.

**Tech Stack:** Kotlin, SQLiteIndex (lsp:indexing), ILlamaController (llama-api), Coroutines

## Global Constraints

- Minimum SDK: API 26
- Target SDK: API 34
- Kotlin version: 1.9.20+
- JVM target: 17
- Embeddings dimension: 384 floats
- Supported file types: .kt, .java, .xml
- Max files for indexing: 100 (MVP limit)
- Search result limit: 20 per query
- Similarity threshold: 0.0 (all results)
- Database location: {cacheDir}/embeddings.db
- No changes to existing keyword search functionality
- Keep VectorSearchTestCommand for debugging

---

### Task 1: Copy vector-search Module from convert-plugin-to-library Branch

**Files:**
- Copy: `vector-search/` (entire directory from convert-plugin-to-library)
  - `vector-search/build.gradle.kts`
  - `vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/`
    - `CodeChunker.kt`
    - `CodeEmbedding.kt`
    - `CodeEmbeddingDescriptor.kt`
    - `VectorMath.kt`
    - `VectorSearchService.kt`
    - `EmbeddingIndexingService.kt`
  - `vector-search/src/test/kotlin/org/appdevforall/codeonthego/vectorsearch/`
    - `CodeChunkerTest.kt`
    - `VectorMathTest.kt`
    - `VectorSearchDemoTest.kt`
    - `VectorSearchServiceTest.kt`

**Interfaces:**
- Consumes: None (first task)
- Produces: vector-search module with VectorSearchService, CodeChunker, CodeEmbedding, VectorMath

- [ ] **Step 1: Create vector-search directory**

```bash
mkdir -p vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch
mkdir -p vector-search/src/test/kotlin/org/appdevforall/codeonthego/vectorsearch
```

- [ ] **Step 2: Copy build.gradle.kts**

```bash
git show convert-plugin-to-library:vector-search/build.gradle.kts > vector-search/build.gradle.kts
```

- [ ] **Step 3: Copy main source files**

```bash
git show convert-plugin-to-library:vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/CodeChunker.kt > vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/CodeChunker.kt

git show convert-plugin-to-library:vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/CodeEmbedding.kt > vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/CodeEmbedding.kt

git show convert-plugin-to-library:vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/CodeEmbeddingDescriptor.kt > vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/CodeEmbeddingDescriptor.kt

git show convert-plugin-to-library:vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/VectorMath.kt > vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/VectorMath.kt

git show convert-plugin-to-library:vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/VectorSearchService.kt > vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/VectorSearchService.kt

git show convert-plugin-to-library:vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/EmbeddingIndexingService.kt > vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/EmbeddingIndexingService.kt
```

- [ ] **Step 4: Copy test files**

```bash
git show convert-plugin-to-library:vector-search/src/test/kotlin/org/appdevforall/codeonthego/vectorsearch/CodeChunkerTest.kt > vector-search/src/test/kotlin/org/appdevforall/codeonthego/vectorsearch/CodeChunkerTest.kt

git show convert-plugin-to-library:vector-search/src/test/kotlin/org/appdevforall/codeonthego/vectorsearch/VectorMathTest.kt > vector-search/src/test/kotlin/org/appdevforall/codeonthego/vectorsearch/VectorMathTest.kt

git show convert-plugin-to-library:vector-search/src/test/kotlin/org/appdevforall/codeonthego/vectorsearch/VectorSearchDemoTest.kt > vector-search/src/test/kotlin/org/appdevforall/codeonthego/vectorsearch/VectorSearchDemoTest.kt

git show convert-plugin-to-library:vector-search/src/test/kotlin/org/appdevforall/codeonthego/vectorsearch/VectorSearchServiceTest.kt > vector-search/src/test/kotlin/org/appdevforall/codeonthego/vectorsearch/VectorSearchServiceTest.kt
```

- [ ] **Step 5: Fix VectorSearchService to use stage branch API**

The convert-plugin-to-library branch uses `llamaController.generateEmbedding()` but stage branch ILlamaController has `encodeForEmbeddings()`. Update VectorSearchService:

```kotlin
// In VectorSearchService.kt, replace:
val queryEmbedding = llamaController.generateEmbedding(query)

// With:
val queryEmbedding = llamaController.encodeForEmbeddings(query)
```

Use Edit tool to make this replacement in all three search methods (search, searchInFile, searchByLanguage).

- [ ] **Step 6: Verify copied files exist**

```bash
ls -la vector-search/src/main/kotlin/org/appdevforall/codeonthego/vectorsearch/
ls -la vector-search/src/test/kotlin/org/appdevforall/codeonthego/vectorsearch/
```

Expected output:
```
CodeChunker.kt
CodeEmbedding.kt
CodeEmbeddingDescriptor.kt
VectorMath.kt
VectorSearchService.kt
EmbeddingIndexingService.kt

CodeChunkerTest.kt
VectorMathTest.kt
VectorSearchDemoTest.kt
VectorSearchServiceTest.kt
```

- [ ] **Step 7: Commit**

```bash
git add vector-search/
git commit -m "feat: add vector-search module from convert-plugin-to-library

Copy complete vector-search module including:
- VectorSearchService for semantic search
- CodeChunker for splitting files into chunks
- CodeEmbedding data model
- VectorMath for cosine similarity
- EmbeddingIndexingService
- All unit tests

Adapted VectorSearchService to use stage branch ILlamaController API
(encodeForEmbeddings instead of generateEmbedding).

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 2: Register vector-search Module in Build System

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: vector-search module from Task 1
- Produces: vector-search module registered in build system, available to app module

- [ ] **Step 1: Add module to settings.gradle.kts**

```kotlin
// At the end of settings.gradle.kts, add:
include(":vector-search")
```

- [ ] **Step 2: Add dependency to app/build.gradle.kts**

Find the dependencies block and add:

```kotlin
dependencies {
    // ... existing dependencies ...

    // Vector search support
    implementation(project(":vector-search"))

    // ... rest of dependencies ...
}
```

- [ ] **Step 3: Sync Gradle**

```bash
./gradlew --stop
./gradlew :vector-search:dependencies
```

Expected: Task completes successfully, no errors

- [ ] **Step 4: Build vector-search module**

```bash
./gradlew :vector-search:build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run vector-search tests**

```bash
./gradlew :vector-search:test
```

Expected: Tests pass (some may be skipped if they need ILlamaController mock)

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts
git commit -m "build: register vector-search module

Add vector-search module to settings.gradle.kts and link it as
dependency in app module. Module builds successfully.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 3: Create Production VectorSearchCommand

**Files:**
- Create: `app/src/main/java/com/itsaky/androidide/api/commands/VectorSearchCommand.kt`

**Interfaces:**
- Consumes:
  - `VectorSearchService.search(query: String, limit: Int, threshold: Float): List<CodeEmbedding>` from Task 1
  - `CodeChunker.chunkFile(file: File): List<CodeChunk>` from Task 1
  - `LlmInferenceEngine.generateEmbeddings(text: String): FloatArray` (existing)
  - `IProjectManager.getInstance().projectDir` (existing)
- Produces:
  - `VectorSearchCommand.execute(): ToolResult` (returns List<CodeEmbedding> in data field)
  - Handles indexing and search in one command

- [ ] **Step 1: Create VectorSearchCommand file**

```kotlin
/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.api.commands

import android.content.Context
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.repository.LlmInferenceEngine
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.indexing.SQLiteIndex
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.vectorsearch.CodeChunker
import org.appdevforall.codeonthego.vectorsearch.CodeEmbedding
import org.appdevforall.codeonthego.vectorsearch.CodeEmbeddingDescriptor
import org.appdevforall.codeonthego.vectorsearch.VectorSearchService
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Production vector search command for semantic code search.
 * Indexes project files on first search, then performs semantic search using embeddings.
 */
class VectorSearchCommand(
    private val query: String,
    private val llmEngine: LlmInferenceEngine,
    private val limit: Int = 20,
    private val context: Context = BaseApplication.baseInstance
) : Command<List<CodeEmbedding>> {

    private val log = LoggerFactory.getLogger(VectorSearchCommand::class.java)

    companion object {
        private const val MAX_FILES_TO_INDEX = 100
        private val SUPPORTED_EXTENSIONS = setOf("kt", "java", "xml")

        @Volatile
        private var embeddingIndex: SQLiteIndex<CodeEmbedding>? = null

        @Volatile
        private var vectorSearchService: VectorSearchService? = null

        private val indexLock = Any()
    }

    override fun execute(): ToolResult = runBlocking {
        try {
            // Validate model is loaded
            if (!llmEngine.isModelLoaded) {
                return@runBlocking ToolResult.failure(
                    message = "No model loaded. Please load a model in AI Settings."
                )
            }

            // Get project directory
            val projectDir = IProjectManager.getInstance().projectDir
            if (!projectDir.exists()) {
                return@runBlocking ToolResult.failure(
                    message = "Project directory not found"
                )
            }

            log.info("Starting vector search for query: '$query'")

            // Initialize index and service if needed
            val index = getOrCreateIndex()
            val service = getOrCreateService()

            // Check if project is indexed
            val existingEmbeddings = withContext(Dispatchers.IO) {
                index.query(IndexQuery.ALL)
            }

            if (existingEmbeddings.isEmpty()) {
                log.info("No existing embeddings found, indexing project...")
                indexProject(projectDir, index)
            } else {
                log.info("Using existing index with ${existingEmbeddings.size} embeddings")
            }

            // Perform search
            val results = service.search(query, limit = limit, threshold = 0.0f)

            log.info("Vector search returned ${results.size} results")

            ToolResult.success(
                message = "Found ${results.size} semantic matches",
                data = results
            )
        } catch (e: Exception) {
            log.error("Vector search failed", e)
            ToolResult.failure(
                message = "Vector search failed: ${e.message}",
                error_details = e.stackTraceToString()
            )
        }
    }

    private fun getOrCreateIndex(): SQLiteIndex<CodeEmbedding> {
        return synchronized(indexLock) {
            embeddingIndex ?: run {
                val dbPath = context.cacheDir.resolve("embeddings.db")
                val newIndex = SQLiteIndex(
                    descriptor = CodeEmbeddingDescriptor,
                    context = context,
                    dbName = dbPath.absolutePath,
                    name = "sqlite:${CodeEmbeddingDescriptor.name}"
                )
                embeddingIndex = newIndex
                log.info("Created embedding index at: $dbPath")
                newIndex
            }
        }
    }

    private fun getOrCreateService(): VectorSearchService {
        return synchronized(indexLock) {
            vectorSearchService ?: run {
                val index = getOrCreateIndex()
                val newService = VectorSearchService(
                    index = index,
                    llamaController = llmEngine.getLlamaController()
                        ?: throw IllegalStateException("LlamaController not available")
                )
                vectorSearchService = newService
                log.info("Created VectorSearchService")
                newService
            }
        }
    }

    private suspend fun indexProject(
        projectDir: File,
        index: SQLiteIndex<CodeEmbedding>
    ) = withContext(Dispatchers.IO) {
        log.info("Indexing project directory: ${projectDir.absolutePath}")

        // Collect source files (limit for MVP)
        val sourceFiles = collectSourceFiles(projectDir, MAX_FILES_TO_INDEX)
        log.info("Found ${sourceFiles.size} files to index")

        var totalChunks = 0
        var successfulFiles = 0
        var failedFiles = 0

        for ((fileIndex, file) in sourceFiles.withIndex()) {
            try {
                val language = when (file.extension.lowercase()) {
                    "kt" -> "kotlin"
                    "java" -> "java"
                    "xml" -> "xml"
                    else -> "unknown"
                }

                // Chunk the file
                val chunks = CodeChunker.chunkFile(file)

                // Generate embeddings for each chunk
                for ((chunkIndex, chunk) in chunks.withIndex()) {
                    try {
                        val embedding = llmEngine.generateEmbeddings(chunk.text)

                        if (embedding.isEmpty()) {
                            log.warn("Empty embedding for file: ${file.name}, chunk $chunkIndex")
                            continue
                        }

                        val codeEmbedding = CodeEmbedding(
                            key = "${file.absolutePath}:$chunkIndex",
                            sourceId = file.absolutePath,
                            filePath = file.absolutePath,
                            chunkText = chunk.text,
                            language = language,
                            chunkIndex = chunkIndex,
                            startLine = chunk.startLine,
                            endLine = chunk.endLine,
                            embedding = embedding
                        )

                        index.index(codeEmbedding)
                        totalChunks++
                    } catch (e: Exception) {
                        log.warn("Failed to embed chunk $chunkIndex in ${file.name}", e)
                    }
                }

                successfulFiles++
                log.info("Indexed [${fileIndex + 1}/${sourceFiles.size}]: ${file.name} (${chunks.size} chunks)")
            } catch (e: Exception) {
                failedFiles++
                log.warn("Failed to index file: ${file.name}", e)
            }
        }

        log.info("Indexing complete: $successfulFiles files, $totalChunks chunks (failed: $failedFiles)")
    }

    private fun collectSourceFiles(projectDir: File, limit: Int): List<File> {
        return projectDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .filter { !it.path.contains("/build/") && !it.path.contains("/.") }
            .take(limit)
            .toList()
    }
}
```

- [ ] **Step 2: Add getLlamaController to LlmInferenceEngine**

VectorSearchService needs direct access to ILlamaController. Add this method to LlmInferenceEngine:

```kotlin
// In app/src/main/java/.../agent/repository/LlmInferenceEngine.kt
// Add this method:

fun getLlamaController(): ILlamaController? {
    return llamaController
}
```

- [ ] **Step 3: Build to verify compilation**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/itsaky/androidide/api/commands/VectorSearchCommand.kt app/src/main/java/com/itsaky/androidide/agent/repository/LlmInferenceEngine.kt
git commit -m "feat: add production VectorSearchCommand

Implement production-ready vector search command that:
- Initializes SQLiteIndex for embeddings on first use
- Checks if project is already indexed
- Indexes project files on first search (max 100 files)
- Performs semantic search using VectorSearchService
- Returns CodeEmbedding results

Also add getLlamaController() accessor to LlmInferenceEngine for
VectorSearchService initialization.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 4: Add performSemanticSearch to EditorViewModel

**Files:**
- Modify: `app/src/main/java/com/itsaky/androidide/viewmodel/EditorViewModel.kt`

**Interfaces:**
- Consumes:
  - `VectorSearchCommand.execute(): ToolResult` from Task 3
  - `LlmInferenceEngineProvider.instance` (existing)
  - `vectorSearchResults: MutableStateFlow<Map<File, List<SearchResult>>>` (existing)
- Produces:
  - `performSemanticSearch(query: String)` - public method to trigger vector search
  - Updates `vectorSearchResults` StateFlow with search results

- [ ] **Step 1: Add import statements to EditorViewModel**

```kotlin
// At top of EditorViewModel.kt, add these imports:
import com.itsaky.androidide.api.commands.VectorSearchCommand
import com.itsaky.androidide.agent.repository.LlmInferenceEngineProvider
import org.appdevforall.codeonthego.vectorsearch.CodeEmbedding
```

- [ ] **Step 2: Add performSemanticSearch method**

Find the section with `onVectorSearchResultsReady` and add this method after it:

```kotlin
/**
 * Performs semantic code search using vector embeddings.
 * Indexes project on first search, then uses cached embeddings for subsequent searches.
 * Updates vectorSearchResults StateFlow with results.
 *
 * @param query The search query (e.g., "authentication", "database")
 */
fun performSemanticSearch(query: String) {
    if (query.isBlank()) {
        _vectorSearchResults.value = emptyMap()
        return
    }

    viewModelScope.launch {
        try {
            // Get LLM engine
            val engine = LlmInferenceEngineProvider.instance

            // Ensure model is loaded
            if (!engine.isModelLoaded) {
                log.warn("Cannot perform semantic search: model not loaded")
                _vectorSearchResults.value = emptyMap()
                return@launch
            }

            // Execute vector search command
            val command = VectorSearchCommand(query, engine, limit = 20)
            val result = withContext(Dispatchers.IO) {
                command.execute()
            }

            // Convert CodeEmbedding results to SearchResult
            if (result.success) {
                @Suppress("UNCHECKED_CAST")
                val embeddings = result.data as? List<CodeEmbedding> ?: emptyList()
                val searchResults = convertEmbeddingsToSearchResults(embeddings)
                _vectorSearchResults.value = searchResults
                log.info("Semantic search completed: ${embeddings.size} results")
            } else {
                log.warn("Semantic search failed: ${result.message}")
                _vectorSearchResults.value = emptyMap()
            }
        } catch (e: Exception) {
            log.error("Semantic search error", e)
            _vectorSearchResults.value = emptyMap()
        }
    }
}

/**
 * Converts CodeEmbedding results to SearchResult format for UI display.
 */
private fun convertEmbeddingsToSearchResults(
    embeddings: List<CodeEmbedding>
): Map<File, List<SearchResult>> {
    return embeddings
        .groupBy { File(it.filePath) }
        .mapValues { (_, chunks) ->
            chunks.map { chunk ->
                SearchResult(
                    file = File(chunk.filePath),
                    lineNumber = chunk.startLine,
                    columnNumber = 0,
                    length = chunk.chunkText.length,
                    match = chunk.chunkText.take(100).trim() // Preview text
                )
            }
        }
}
```

- [ ] **Step 3: Add logger if not present**

Check if EditorViewModel has a logger. If not, add at the top of the class:

```kotlin
private val log = org.slf4j.LoggerFactory.getLogger(EditorViewModel::class.java)
```

- [ ] **Step 4: Build to verify compilation**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/itsaky/androidide/viewmodel/EditorViewModel.kt
git commit -m "feat: add semantic search to EditorViewModel

Add performSemanticSearch() method that:
- Validates model is loaded
- Executes VectorSearchCommand
- Converts CodeEmbedding results to SearchResult
- Updates vectorSearchResults StateFlow

Includes convertEmbeddingsToSearchResults() helper for format conversion.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 5: Wire Semantic Search to Search UI

**Files:**
- Find and modify: Search trigger point (likely BaseEditorActivity or search controller)

**Interfaces:**
- Consumes:
  - `EditorViewModel.performSemanticSearch(query: String)` from Task 4
  - Existing keyword search trigger point
- Produces:
  - Both keyword and semantic search triggered on user search action
  - Results appear in SearchResultFragment automatically (no UI changes needed)

- [ ] **Step 1: Find the search trigger point**

```bash
grep -rn "onSearchResultsReady\|performSearch" app/src/main/java/com/itsaky/androidide/activities/editor/ --include="*.kt" | head -20
```

Identify the file and method where keyword search is triggered.

- [ ] **Step 2: Examine the search trigger code**

Read the identified file to understand how search is currently triggered. Look for where a search query is processed and results are updated.

- [ ] **Step 3: Add semantic search call**

In the same location where keyword search is triggered, add a call to performSemanticSearch. The code should look similar to:

```kotlin
// Existing keyword search trigger:
editorViewModel.onSearchResultsReady(keywordResults)

// Add immediately after:
editorViewModel.performSemanticSearch(query)
```

Both searches run in parallel - keyword search synchronously, semantic search asynchronously.

- [ ] **Step 4: Test search trigger (manual)**

If you can build and run the app:
1. Open a project
2. Open search UI
3. Enter a query
4. Verify both sections update in SearchResultFragment

If building is blocked, proceed to commit and note testing requirements.

- [ ] **Step 5: Build to verify compilation**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add <modified-file>
git commit -m "feat: wire semantic search to search UI

Add call to performSemanticSearch() alongside existing keyword search.
Both searches now run when user performs a search:
- Keyword search (existing, fast)
- Semantic search (new, may take time on first search)

Results appear in their respective sections in SearchResultFragment.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 6: Manual Testing and Verification

**Files:**
- No code changes (testing task)

**Interfaces:**
- Consumes: Complete integrated system from Tasks 1-5
- Produces: Verified working semantic search feature

- [ ] **Step 1: Build and install app**

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: Installation succeeds

- [ ] **Step 2: Load a model**

1. Launch app
2. Go to AI Settings
3. Select and load an encoder model (e.g., all-MiniLM-L6-v2)
4. Verify model loads successfully

- [ ] **Step 3: Test first search (indexing)**

1. Open a project
2. Open search UI
3. Enter query: "main"
4. Observe:
   - Keyword results appear immediately
   - Vector search indexing starts (may show loading)
   - After indexing, vector results appear
5. Check logcat:

```bash
adb logcat | grep -E "VectorSearchCommand|VectorSearchService|Indexing"
```

Expected: See indexing progress and completion messages

- [ ] **Step 4: Test subsequent search (cached)**

1. Enter query: "database"
2. Observe:
   - Both keyword and vector results appear
   - Vector results appear quickly (no indexing delay)

- [ ] **Step 5: Verify search quality**

Test semantic understanding:
- Query "authentication" → should find auth-related code even if keyword "authentication" doesn't appear
- Query "click handler" → should find onClick, setOnClickListener methods
- Query "database" → should find database-related code

- [ ] **Step 6: Verify result navigation**

1. Click on a vector search result
2. Verify file opens at correct line number

- [ ] **Step 7: Test debug command**

1. Open chat interface
2. Click menu → "Test Vector Search"
3. Check logcat for detailed results
4. Verify test command still works independently

- [ ] **Step 8: Verify persistence**

1. Close and reopen app
2. Perform a search
3. Verify vector results appear immediately (no re-indexing)

- [ ] **Step 9: Check database file**

```bash
adb shell ls -lh /data/data/com.itsaky.androidide/cache/embeddings.db
```

Expected: File exists with size > 0

- [ ] **Step 10: Document test results**

Create a test summary noting:
- ✅ First search triggers indexing
- ✅ Subsequent searches use cache
- ✅ Semantic search finds relevant results
- ✅ Results clickable and navigate correctly
- ✅ Test command works
- ✅ Embeddings persist across app restarts
- ⚠️ Any issues or edge cases discovered

---

### Task 7: Final Integration and Cleanup

**Files:**
- Review all modified files
- Optional: Update VectorSearchTestCommand if needed

**Interfaces:**
- Consumes: Verified working system from Task 6
- Produces: Production-ready vector search feature

- [ ] **Step 1: Review all changes**

```bash
git diff HEAD~7 --stat
```

Verify all expected files were modified/created:
- vector-search/ module added
- settings.gradle.kts modified
- app/build.gradle.kts modified
- VectorSearchCommand.kt created
- EditorViewModel.kt modified
- Search trigger point modified

- [ ] **Step 2: Run full test suite**

```bash
./gradlew test
```

Expected: All tests pass (some vector-search tests may be skipped if they require real ILlamaController)

- [ ] **Step 3: Verify no regressions in keyword search**

1. Launch app
2. Perform keyword search (exact text match)
3. Verify keyword results section still works correctly
4. Verify performance is not degraded

- [ ] **Step 4: Optional: Update VectorSearchTestCommand**

If desired, update the test command to use the production VectorSearchService instead of its own implementation. This is optional since keeping it separate is also valid for debugging.

- [ ] **Step 5: Clean build**

```bash
./gradlew clean
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Final commit (if any changes)**

```bash
git add .
git commit -m "chore: final integration cleanup

Clean up any remaining loose ends from vector search integration.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Create summary document**

Document the completed integration:
- Features added
- Files modified
- Testing results
- Known limitations (e.g., 100 file limit)
- Future enhancement opportunities

---

## Implementation Notes

### Error Handling Strategy
- Model not loaded → Silent fail, log warning, return empty results
- Project not found → ToolResult.failure
- Indexing fails for some files → Log warning, skip file, continue
- Search fails → Log error, return empty results
- Database errors → Propagate exception to caller

### Performance Expectations
- First search: 5-30 seconds (indexing 100 files)
- Subsequent searches: 100-500ms
- Memory usage: ~1.5MB for 1000 embeddings
- Database size: ~1.5MB on disk

### Testing Strategy
- Unit tests in vector-search module (copied from convert-plugin-to-library)
- Integration test: Full search flow with real project
- Manual test: Semantic search quality verification
- Edge case test: Empty project, no model loaded, large project

### Rollback Steps
If critical issues found:
1. Comment out `editorViewModel.performSemanticSearch(query)` in search trigger
2. Remove VectorSearchCommand initialization
3. Keep vector-search module for future fix attempt

## Success Criteria

✅ vector-search module builds successfully
✅ First search triggers indexing with progress indication
✅ Subsequent searches use cached embeddings (fast)
✅ Search results appear in SearchResultFragment vector section
✅ Results are semantically relevant to query
✅ Clicking result opens file at correct line
✅ VectorSearchTestCommand still works for debugging
✅ No regressions in existing keyword search
✅ Embeddings persist across app restarts

## Future Enhancements (Out of Scope)

- Background indexing on project open
- Incremental re-indexing on file changes
- Progress indicator during indexing
- Manual re-index menu option
- Search filters (language, file type)
- Hybrid ranking (combine keyword + vector scores)
- FAISS integration for faster search
