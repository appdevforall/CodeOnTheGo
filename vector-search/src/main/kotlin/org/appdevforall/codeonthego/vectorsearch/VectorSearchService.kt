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

package org.appdevforall.codeonthego.vectorsearch

import com.itsaky.androidide.llamacpp.api.ILlamaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.indexing.SQLiteIndex
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Semantic search service for code using embeddings.
 *
 * Provides semantic search across a code embedding index by converting
 * queries to embeddings and computing cosine similarity with stored embeddings.
 *
 * @param index The SQLiteIndex containing CodeEmbedding entries
 * @param llamaController The LLM controller for generating query embeddings
 */
class VectorSearchService(
    private val index: SQLiteIndex<CodeEmbedding>,
    private val llamaController: ILlamaController,
) {

    companion object {
        private val log = LoggerFactory.getLogger(VectorSearchService::class.java)
    }

    /**
     * Search result with similarity score for debugging and ranking.
     */
    data class SearchResult(
        val codeEmbedding: CodeEmbedding,
        val similarity: Float
    )

    /**
     * Performs semantic search across all indexed code.
     *
     * @param query Search query string
     * @param limit Maximum number of results to return
     * @param threshold Minimum cosine similarity score (0.0 to 1.0). Defaults to 0.0.
     * @return List of CodeEmbedding results, sorted by similarity (highest first)
     * @throws IllegalArgumentException if query is empty
     */
    suspend fun search(
        query: String,
        limit: Int = 20,
        threshold: Float = 0.0f,
    ): List<CodeEmbedding> {
        if (query.isBlank()) {
            throw IllegalArgumentException("Query cannot be empty")
        }

        log.debug("Search started with query: '{}' (limit={}, threshold={})", query, limit, threshold)

        val results = withContext(Dispatchers.Default) {
            // Generate embedding for the query
            val queryEmbedding = llamaController.generateEmbedding(query)

            if (queryEmbedding.isEmpty()) {
                log.debug("Empty embedding returned")
                return@withContext emptyList()
            }

            log.debug("Generated query embedding with dimension: {}", queryEmbedding.size)

            // Query all embeddings and compute similarity
            val resultsWithScores = index.query(IndexQuery.ALL)
                .map { codeEmbedding ->
                    val similarity = VectorMath.cosineSimilarity(queryEmbedding, codeEmbedding.embedding)
                    Pair(codeEmbedding, similarity)
                }
                .filter { (_, similarity) -> similarity >= threshold }
                .sortedByDescending { (_, similarity) -> similarity }
                .take(limit)
                .toList()

            // Log top results with similarity scores for debugging
            if (resultsWithScores.isNotEmpty()) {
                log.debug("Top 5 results:")
                resultsWithScores.take(5).forEachIndexed { index, (embedding, score) ->
                    log.debug("  [{}] {} - similarity: {:.3f}", index + 1,
                        File(embedding.filePath).name, score)
                }
            }

            log.debug("Search returned {} results (threshold: {})", resultsWithScores.size, threshold)

            // Return just the embeddings (for backward compatibility)
            resultsWithScores.map { it.first }
        }

        return results
    }

    /**
     * Performs semantic search and returns results with similarity scores.
     *
     * @param query Search query string
     * @param limit Maximum number of results to return
     * @param threshold Minimum cosine similarity score (0.0 to 1.0). Defaults to 0.3.
     * @return List of SearchResult with similarity scores, sorted by similarity (highest first)
     * @throws IllegalArgumentException if query is empty
     */
    suspend fun searchWithScores(
        query: String,
        limit: Int = 20,
        threshold: Float = 0.3f,
    ): List<SearchResult> {
        if (query.isBlank()) {
            throw IllegalArgumentException("Query cannot be empty")
        }

        log.debug("Search with scores started: '{}' (limit={}, threshold={})", query, limit, threshold)

        val results = withContext(Dispatchers.Default) {
            // Generate embedding for the query
            val queryEmbedding = llamaController.generateEmbedding(query)

            if (queryEmbedding.isEmpty()) {
                log.debug("Empty embedding returned")
                return@withContext emptyList()
            }

            log.debug("Generated query embedding with dimension: {}", queryEmbedding.size)

            // Query all embeddings and compute similarity
            val allResults = index.query(IndexQuery.ALL)
                .map { codeEmbedding ->
                    val similarity = VectorMath.cosineSimilarity(queryEmbedding, codeEmbedding.embedding)
                    SearchResult(codeEmbedding, similarity)
                }
                .filter { it.similarity >= threshold }
                .sortedByDescending { it.similarity }
                .take(limit)
                .toList()

            // Log top results
            if (allResults.isNotEmpty()) {
                log.debug("Top 5 results with scores:")
                allResults.take(5).forEachIndexed { index, result ->
                    log.debug("  [{}] {} - {:.3f}", index + 1,
                        File(result.codeEmbedding.filePath).name, result.similarity)
                }
            }

            log.debug("Search with scores returned {} results", allResults.size)
            allResults
        }

        return results
    }

    /**
     * Performs semantic search within a specific source file.
     *
     * @param query Search query string
     * @param filePath Path to the source file to search within
     * @param limit Maximum number of results to return
     * @param threshold Minimum cosine similarity score (0.0 to 1.0). Defaults to 0.0.
     * @return List of CodeEmbedding results from the file, sorted by similarity (highest first)
     * @throws IllegalArgumentException if query is empty
     */
    suspend fun searchInFile(
        query: String,
        filePath: String,
        limit: Int = 20,
        threshold: Float = 0.0f,
    ): List<CodeEmbedding> {
        if (query.isBlank()) {
            throw IllegalArgumentException("Query cannot be empty")
        }

        log.debug("Search in file started with query: '{}', file: '{}' (limit={}, threshold={})",
            query, filePath, limit, threshold)

        val results = withContext(Dispatchers.Default) {
            // Generate embedding for the query
            val queryEmbedding = llamaController.generateEmbedding(query)

            if (queryEmbedding.isEmpty()) {
                log.debug("Empty embedding returned")
                return@withContext emptyList()
            }

            log.debug("Generated query embedding with dimension: {}", queryEmbedding.size)

            // Query embeddings from specific file
            val fileQuery = IndexQuery(sourceId = filePath, limit = 0)
            val allResults = index.query(fileQuery)
                .map { codeEmbedding ->
                    val similarity = VectorMath.cosineSimilarity(queryEmbedding, codeEmbedding.embedding)
                    Pair(codeEmbedding, similarity)
                }
                .filter { (_, similarity) -> similarity >= threshold }
                .sortedByDescending { (_, similarity) -> similarity }
                .take(limit)
                .map { (codeEmbedding, _) -> codeEmbedding }
                .toList()

            log.debug("Search in file returned {} results", allResults.size)
            allResults
        }

        return results
    }

    /**
     * Performs semantic search within a specific programming language.
     *
     * @param query Search query string
     * @param language Programming language (e.g., "kotlin", "java", "python")
     * @param limit Maximum number of results to return
     * @param threshold Minimum cosine similarity score (0.0 to 1.0). Defaults to 0.0.
     * @return List of CodeEmbedding results from the language, sorted by similarity (highest first)
     * @throws IllegalArgumentException if query is empty
     */
    suspend fun searchByLanguage(
        query: String,
        language: String,
        limit: Int = 20,
        threshold: Float = 0.0f,
    ): List<CodeEmbedding> {
        if (query.isBlank()) {
            throw IllegalArgumentException("Query cannot be empty")
        }

        log.debug("Search by language started with query: '{}', language: '{}' (limit={}, threshold={})",
            query, language, limit, threshold)

        val results = withContext(Dispatchers.Default) {
            // Generate embedding for the query
            val queryEmbedding = llamaController.generateEmbedding(query)

            if (queryEmbedding.isEmpty()) {
                log.debug("Empty embedding returned")
                return@withContext emptyList()
            }

            log.debug("Generated query embedding with dimension: {}", queryEmbedding.size)

            // Query embeddings for specific language using exact match
            val languageQuery = IndexQuery(
                exactMatch = mapOf("language" to language),
                limit = 0
            )
            val allResults = index.query(languageQuery)
                .map { codeEmbedding ->
                    val similarity = VectorMath.cosineSimilarity(queryEmbedding, codeEmbedding.embedding)
                    Pair(codeEmbedding, similarity)
                }
                .filter { (_, similarity) -> similarity >= threshold }
                .sortedByDescending { (_, similarity) -> similarity }
                .take(limit)
                .map { (codeEmbedding, _) -> codeEmbedding }
                .toList()

            log.debug("Search by language returned {} results", allResults.size)
            allResults
        }

        return results
    }
}
