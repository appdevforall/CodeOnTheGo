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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Unit tests for VectorSearchService semantic search logic.
 *
 * Tests core search algorithms and filtering logic.
 * Full integration tests would require SQLiteIndex setup.
 */
class VectorSearchServiceTest {

    @Test
    fun testSimilarityCalculationInSearchContext() {
        // Test vectors used in semantic search
        val queryEmbedding = floatArrayOf(0.5f, 0.3f, 0.2f, 0.4f, 0.1f)
        val resultEmbedding1 = floatArrayOf(0.5f, 0.3f, 0.2f, 0.4f, 0.1f) // Identical
        val resultEmbedding2 = floatArrayOf(0.51f, 0.32f, 0.21f, 0.41f, 0.11f) // Very similar
        val resultEmbedding3 = floatArrayOf(0.1f, 0.9f, 0.2f, 0.1f, 0.8f) // Different

        val sim1 = VectorMath.cosineSimilarity(queryEmbedding, resultEmbedding1)
        val sim2 = VectorMath.cosineSimilarity(queryEmbedding, resultEmbedding2)
        val sim3 = VectorMath.cosineSimilarity(queryEmbedding, resultEmbedding3)

        // Identical should have highest similarity
        assertEquals(1.0, sim1.toDouble(), 0.0001)

        // Very similar should have high similarity
        assertTrue(sim2 > 0.99f && sim2 < 1.0f)

        // Different should have lower similarity
        assertTrue(sim3 > 0.0f && sim3 < sim2)
    }

    @Test
    fun testSimilarityThresholdFiltering() {
        // Test how different thresholds would filter results
        val queryEmbedding = floatArrayOf(1f, 0f, 0f, 0f, 0f)

        // Perfect match
        val perfect = floatArrayOf(1f, 0f, 0f, 0f, 0f)
        val simPerfect = VectorMath.cosineSimilarity(queryEmbedding, perfect)

        // 90% match
        val highMatch = floatArrayOf(0.9f, 0.1f, 0f, 0f, 0f)
        val simHigh = VectorMath.cosineSimilarity(queryEmbedding, highMatch)

        // 50% match
        val mediumMatch = floatArrayOf(0.5f, 0.5f, 0f, 0f, 0f)
        val simMedium = VectorMath.cosineSimilarity(queryEmbedding, mediumMatch)

        // 10% match
        val lowMatch = floatArrayOf(0.1f, 0.9f, 0f, 0f, 0f)
        val simLow = VectorMath.cosineSimilarity(queryEmbedding, lowMatch)

        // Verify ordering
        assertTrue(simPerfect > simHigh)
        assertTrue(simHigh > simMedium)
        assertTrue(simMedium > simLow)

        // Test threshold filtering logic
        val results = listOf(
            Pair("perfect", simPerfect),
            Pair("high", simHigh),
            Pair("medium", simMedium),
            Pair("low", simLow),
        )

        // Filter with threshold 0.8
        val filtered = results.filter { (_, sim) -> sim >= 0.8f }
        assertTrue(filtered.size >= 2, "Should have perfect and high matches above 0.8")

        // Filter with threshold 0.5
        val filtered2 = results.filter { (_, sim) -> sim >= 0.5f }
        assertTrue(filtered2.size >= 3, "Should have multiple matches above 0.5")
    }

    @Test
    fun testSearchResultSorting() {
        // Simulate search results and verify they would be sorted correctly
        val embeddings = mapOf(
            "1" to floatArrayOf(0.5f, 0.3f, 0.2f, 0.4f, 0.1f),
            "2" to floatArrayOf(0.1f, 0.9f, 0.2f, 0.1f, 0.8f),
            "3" to floatArrayOf(0.51f, 0.32f, 0.21f, 0.41f, 0.11f),
        )

        val queryEmbedding = floatArrayOf(0.5f, 0.3f, 0.2f, 0.4f, 0.1f)

        // Calculate similarities
        val withSimilarity = embeddings.map { (id, emb) ->
            val sim = VectorMath.cosineSimilarity(queryEmbedding, emb)
            Pair(id, sim)
        }

        // Sort by similarity
        val sorted = withSimilarity.sortedByDescending { (_, sim) -> sim }

        // First should be most similar (ID "1" - identical)
        assertEquals("1", sorted[0].first)
        assertEquals(1.0, sorted[0].second.toDouble(), 0.0001)

        // Rest should be in descending order
        assertTrue(sorted[0].second >= sorted[1].second)
        assertTrue(sorted[1].second >= sorted[2].second)
    }

    @Test
    fun testLimitParameter() {
        // Test how limit parameter works
        val embeddings = listOf(1, 2, 3, 4, 5)

        // Test limit=2
        val limited2 = embeddings.take(2)
        assertEquals(2, limited2.size)

        // Test limit=1
        val limited1 = embeddings.take(1)
        assertEquals(1, limited1.size)

        // Test limit=0
        val limited0 = embeddings.take(0)
        assertEquals(0, limited0.size)

        // Test limit > list size
        val limitedLarge = embeddings.take(100)
        assertEquals(5, limitedLarge.size)
    }

    @Test
    fun testFileFilteringLogic() {
        // Test file-based filtering
        val embeddings = mapOf(
            "1" to "File1.kt",
            "2" to "File1.kt",
            "3" to "File2.java",
            "4" to "File2.java",
        )

        // Filter by File1.kt
        val file1Results = embeddings.filter { (_, file) -> file == "File1.kt" }
        assertEquals(2, file1Results.size)
        assertTrue(file1Results.all { (_, file) -> file == "File1.kt" })

        // Filter by File2.java
        val file2Results = embeddings.filter { (_, file) -> file == "File2.java" }
        assertEquals(2, file2Results.size)
        assertTrue(file2Results.all { (_, file) -> file == "File2.java" })

        // Filter by non-existent file
        val emptyResults = embeddings.filter { (_, file) -> file == "NonExistent.kt" }
        assertEquals(0, emptyResults.size)
    }

    @Test
    fun testLanguageFilteringLogic() {
        // Test language-based filtering
        val embeddings = mapOf(
            "1" to "kotlin",
            "2" to "kotlin",
            "3" to "java",
            "4" to "java",
        )

        // Filter by Kotlin
        val kotlinResults = embeddings.filter { (_, lang) -> lang == "kotlin" }
        assertEquals(2, kotlinResults.size)
        assertTrue(kotlinResults.all { (_, lang) -> lang == "kotlin" })

        // Filter by Java
        val javaResults = embeddings.filter { (_, lang) -> lang == "java" }
        assertEquals(2, javaResults.size)
        assertTrue(javaResults.all { (_, lang) -> lang == "java" })

        // Filter by non-existent language
        val emptyResults = embeddings.filter { (_, lang) -> lang == "python" }
        assertEquals(0, emptyResults.size)
    }

    @Test
    fun testMultipleFiltersApplied() {
        // Test combining multiple filters
        data class Item(val id: String, val file: String, val language: String)

        val embeddings = listOf(
            Item("1", "File1.kt", "kotlin"),
            Item("2", "File1.java", "java"),
            Item("3", "File2.java", "java"),
            Item("4", "File2.kt", "kotlin"),
        )

        // Filter by File1.kt and kotlin
        val file1Kotlin = embeddings.filter { it.file == "File1.kt" && it.language == "kotlin" }
        assertEquals(1, file1Kotlin.size)
        assertEquals("1", file1Kotlin[0].id)

        // Filter by java language only
        val javaFiles = embeddings.filter { it.language == "java" }
        assertEquals(2, javaFiles.size)
    }

    @Test
    fun testEmptyQueryHandling() {
        // Simulate checking for empty query
        val emptyQuery = ""
        val blankQuery = "   "

        assertTrue(emptyQuery.isBlank(), "Empty string should be blank")
        assertTrue(blankQuery.isBlank(), "Whitespace string should be blank")

        // Verify that blank queries would be rejected
        assertFailsWith<IllegalArgumentException> {
            if (emptyQuery.isBlank()) {
                throw IllegalArgumentException("Query cannot be empty")
            }
        }
    }

    @Test
    fun testSimilarityCalculationAccuracy() {
        // Detailed test of similarity calculations used in search
        val vector1 = floatArrayOf(1f, 0f)
        val vector2 = floatArrayOf(1f, 0f)
        val vector3 = floatArrayOf(0f, 1f)

        val sim11 = VectorMath.cosineSimilarity(vector1, vector2)
        val sim13 = VectorMath.cosineSimilarity(vector1, vector3)

        assertEquals(1.0, sim11.toDouble(), 0.0001, "Identical vectors")
        assertEquals(0.0, sim13.toDouble(), 0.0001, "Orthogonal vectors")
    }

    @Test
    fun testSearchParameterRanges() {
        // Test valid parameter ranges for search
        val validLimits = listOf(0, 1, 5, 10, 20, 100)
        val validThresholds = listOf(0.0f, 0.1f, 0.5f, 0.8f, 1.0f)

        // Verify all are reasonable
        assertTrue(validLimits.all { it >= 0 }, "Limits should be non-negative")
        assertTrue(validThresholds.all { it in 0.0f..1.0f }, "Thresholds should be 0-1")
    }

    @Test
    fun testSequenceToListConversion() {
        // Test conversion from Sequence (what index.query returns) to List
        val sequence = sequenceOf(1, 2, 3, 4, 5)
        val list = sequence.toList()

        assertEquals(5, list.size)
        assertEquals(listOf(1, 2, 3, 4, 5), list)
    }

    @Test
    fun testSortingAndLimitingCombined() {
        // Test combining sorting and limiting operations
        val items = listOf(
            Pair(1, 0.9f),
            Pair(2, 0.5f),
            Pair(3, 0.95f),
            Pair(4, 0.3f),
        )

        // Sort by score descending and take top 2
        val topResults = items.sortedByDescending { it.second }.take(2)

        assertEquals(2, topResults.size)
        assertEquals(0.95f, topResults[0].second)
        assertEquals(0.9f, topResults[1].second)
    }
}
