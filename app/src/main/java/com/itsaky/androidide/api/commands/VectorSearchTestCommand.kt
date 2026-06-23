package com.itsaky.androidide.api.commands

import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.repository.LlmInferenceEngine
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.vectorsearch.CodeChunker
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.sqrt

/**
 * Test command for vector search functionality.
 * Indexes project source files and searches for similar code using embeddings.
 */
class VectorSearchTestCommand(
    private val query: String,
    private val llmEngine: LlmInferenceEngine,
    private val maxFiles: Int = 10
) : Command<String> {

    private val log = LoggerFactory.getLogger(VectorSearchTestCommand::class.java)

    /**
     * Public data class for search results that can be used by other components.
     * Each result is a single chunk (like the production search), not a whole file.
     */
    data class VectorSearchResult(
        val file: File,
        val similarity: Float,
        val snippet: String,
        /** 1-based first line of the matched chunk. */
        val startLine: Int,
        /** 1-based last line of the matched chunk. */
        val endLine: Int
    )

    /**
     * Perform vector search and return structured results.
     * This can be called directly by UI components.
     *
     * @param projectDir The project directory to search in (defaults to current project)
     * @param fileLimit Maximum number of files to index (defaults to maxFiles from constructor)
     * @return List of VectorSearchResult sorted by similarity (descending)
     * @throws Exception if search fails
     */
    suspend fun performSearch(
        projectDir: File = IProjectManager.getInstance().projectDir,
        fileLimit: Int = maxFiles
    ): List<VectorSearchResult> {
        // Check if model is loaded
        if (!llmEngine.isModelLoaded) {
            throw IllegalStateException("No model loaded. Please load an encoder model for vector search.")
        }

        // Validate project directory
        if (!projectDir.exists()) {
            throw IllegalArgumentException("Project directory not found: ${projectDir.absolutePath}")
        }

        // Collect source files
        val sourceFiles = collectSourceFiles(projectDir, fileLimit)
        if (sourceFiles.isEmpty()) {
            return emptyList()
        }

        log.info("Found ${sourceFiles.size} source files to index")

        // Generate embeddings for query
        val queryEmbedding = llmEngine.generateEmbeddings(query)
        if (queryEmbedding.isEmpty()) {
            throw IllegalStateException("Failed to generate embeddings for query. Ensure encoder model is loaded.")
        }

        log.info("Query embedding generated: dimension=${queryEmbedding.size}")

        // Mirror the production search: chunk each file (CodeChunker also strips boilerplate
        // and tracks line ranges) and rank per chunk, so a match localizes to one section.
        val results = mutableListOf<VectorSearchResult>()
        var totalChunks = 0
        for ((index, file) in sourceFiles.withIndex()) {
            try {
                val chunks = CodeChunker.chunkFile(file)
                if (chunks.isEmpty()) {
                    log.debug("No chunks produced for file: ${file.name}")
                    continue
                }

                var bestSimilarity = Float.NEGATIVE_INFINITY
                for (chunk in chunks) {
                    if (chunk.content.isBlank()) continue

                    // Breathing room every 10 chunks, like the production indexer.
                    if (totalChunks > 0 && totalChunks % 10 == 0) {
                        delay(100)
                    }

                    val chunkEmbedding = llmEngine.generateEmbeddings(chunk.content)
                    if (chunkEmbedding.isEmpty()) {
                        log.warn("Failed to generate embedding for ${file.name} chunk ${chunk.startLine}-${chunk.endLine}")
                        continue
                    }
                    totalChunks++

                    val similarity = cosineSimilarity(queryEmbedding, chunkEmbedding)
                    bestSimilarity = maxOf(bestSimilarity, similarity)
                    results.add(
                        VectorSearchResult(
                            file = file,
                            similarity = similarity,
                            snippet = chunk.content,
                            // CodeChunker line numbers are 0-based; display as 1-based.
                            startLine = chunk.startLine + 1,
                            endLine = chunk.endLine + 1
                        )
                    )
                }

                log.info(
                    "Indexed [${index + 1}/${sourceFiles.size}]: ${file.name} (${chunks.size} chunks, best: %.3f)"
                        .format(if (bestSimilarity.isFinite()) bestSimilarity else 0f)
                )
            } catch (e: Exception) {
                log.warn("Failed to process file: ${file.name}", e)
            }
        }

        // Sort by similarity (descending)
        results.sortByDescending { it.similarity }
        return results
    }

    override fun execute(): ToolResult = runBlocking {
        try {
            log.info("Starting vector search test for query: $query")

            // Get project directory
            val projectDir = IProjectManager.getInstance().projectDir

            // Perform the search using the reusable method
            val results = performSearch(projectDir, maxFiles)

            if (results.isEmpty()) {
                return@runBlocking ToolResult.failure(message = "No source files found or indexed in project")
            }

            // Format results for display
            val output = buildString {
                appendLine("=== Vector Search Test Results ===")
                appendLine("Query: $query")
                appendLine("Matched ${results.size} chunks")
                appendLine()
                appendLine("Top matches:")
                results.take(5).forEachIndexed { index, result ->
                    appendLine()
                    appendLine("${index + 1}. ${result.file.name}")
                    appendLine("   Similarity: ${"%.4f".format(result.similarity)}")
                    appendLine("   Lines: ${formatLineRange(result.startLine, result.endLine)}")
                    appendLine("   Path: ${result.file.relativeTo(projectDir).path}")
                    // Collapse whitespace so a multi-line chunk reads as a one-line preview.
                    val preview = result.snippet.replace(Regex("\\s+"), " ").trim().take(100)
                    appendLine("   Snippet: $preview...")
                }
            }

            ToolResult.success(
                message = "Vector search test completed successfully",
                data = output
            )
        } catch (e: Exception) {
            log.error("Vector search test failed", e)
            ToolResult.failure(
                message = "Vector search test failed: ${e.message}",
                error_details = e.stackTraceToString()
            )
        }
    }

    private fun collectSourceFiles(projectDir: File, limit: Int): List<File> {
        val codeExtensions = setOf("kt", "java")
        val extensions = codeExtensions + "xml"
        return projectDir.walkTopDown()
            .filter { it.isFile && it.extension in extensions }
            .filter { !it.path.contains("/build/") && !it.path.contains("/.") }
            // Prefer real source code over resource XML so the indexed sample is
            // representative of code content, instead of whatever the file walk hits first.
            .sortedByDescending { it.extension in codeExtensions }
            .take(limit)
            .toList()
    }

    /** Formats a 1-based inclusive line range, collapsing single-line ranges. */
    private fun formatLineRange(startLine: Int, endLine: Int): String =
        if (endLine > startLine) "lines $startLine-$endLine" else "line $startLine"

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }
}
