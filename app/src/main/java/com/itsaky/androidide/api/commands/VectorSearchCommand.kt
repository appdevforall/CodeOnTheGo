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
        private const val MAX_FILES_TO_INDEX = 500 // Increased from 100 for better coverage
        private val SUPPORTED_EXTENSIONS = setOf("kt", "java", "xml")
        private const val DEFAULT_SIMILARITY_THRESHOLD = 0.05f // Lowered to capture more results with general-purpose models

        @Volatile
        private var embeddingIndex: SQLiteIndex<CodeEmbedding>? = null

        @Volatile
        private var vectorSearchService: VectorSearchService? = null

        private val indexLock = Any()

        // Store last search results for retrieval by EditorViewModel
        @Volatile
        var lastSearchResults: List<CodeEmbedding> = emptyList()
            private set

        // Store last search results with similarity scores
        @Volatile
        var lastSearchResultsWithScores: List<Pair<CodeEmbedding, Float>> = emptyList()
            private set
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
                index.query(IndexQuery.ALL).toList()
            }

            if (existingEmbeddings.isEmpty()) {
                log.info("No existing embeddings found, indexing project...")
                indexProject(projectDir, index)
            } else {
                log.info("Using existing index with ${existingEmbeddings.size} embeddings")
            }

            // Perform search with proper similarity threshold and get scores
            val resultsWithScores = service.searchWithScores(query, limit = limit, threshold = DEFAULT_SIMILARITY_THRESHOLD)

            log.info("Vector search returned ${resultsWithScores.size} results (threshold: $DEFAULT_SIMILARITY_THRESHOLD)")

            // Store results for retrieval (both formats)
            lastSearchResults = resultsWithScores.map { it.codeEmbedding }
            lastSearchResultsWithScores = resultsWithScores.map { it.codeEmbedding to it.similarity }

            // Return success with summary
            ToolResult.success(
                message = "Found ${resultsWithScores.size} semantic matches",
                data = "Found ${resultsWithScores.size} semantic code matches for query: '$query'"
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
                        val embedding = llmEngine.generateEmbeddings(chunk.content)

                        if (embedding.isEmpty()) {
                            log.warn("Empty embedding for file: ${file.name}, chunk $chunkIndex")
                            continue
                        }

                        val codeEmbedding = CodeEmbedding(
                            key = "${file.absolutePath}:$chunkIndex",
                            sourceId = file.absolutePath,
                            filePath = file.absolutePath,
                            chunkText = chunk.content,
                            language = language,
                            chunkIndex = chunkIndex,
                            startLine = chunk.startLine,
                            endLine = chunk.endLine,
                            embedding = embedding
                        )

                        index.insert(codeEmbedding)
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
