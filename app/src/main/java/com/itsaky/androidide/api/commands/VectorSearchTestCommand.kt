package com.itsaky.androidide.api.commands

import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.repository.LlmInferenceEngine
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.runBlocking
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
     */
    data class VectorSearchResult(
        val file: File,
        val similarity: Float,
        val snippet: String
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

        // Index files and compute similarities
        val results = mutableListOf<VectorSearchResult>()
        for ((index, file) in sourceFiles.withIndex()) {
            try {
                val content = file.readText()
                // Take first 500 chars for testing (to keep it fast)
                val snippet = content.take(500)

                val fileEmbedding = llmEngine.generateEmbeddings(snippet)
                if (fileEmbedding.isEmpty()) {
                    log.warn("Failed to generate embedding for file: ${file.name}")
                    continue
                }

                val similarity = cosineSimilarity(queryEmbedding, fileEmbedding)
                results.add(VectorSearchResult(file, similarity, snippet))

                log.info("Indexed [${index + 1}/${sourceFiles.size}]: ${file.name} (similarity: %.3f)".format(similarity))
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
                appendLine("Indexed ${results.size} files")
                appendLine()
                appendLine("Top matches:")
                results.take(5).forEachIndexed { index, result ->
                    appendLine()
                    appendLine("${index + 1}. ${result.file.name}")
                    appendLine("   Similarity: ${"%.4f".format(result.similarity)}")
                    appendLine("   Path: ${result.file.relativeTo(projectDir).path}")
                    appendLine("   Snippet: ${result.snippet.take(100)}...")
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
        val extensions = setOf("kt", "java", "xml")
        return projectDir.walkTopDown()
            .filter { it.isFile && it.extension in extensions }
            .filter { !it.path.contains("/build/") && !it.path.contains("/.") }
            .take(limit)
            .toList()
    }

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
