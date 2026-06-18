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
import kotlin.test.assertTrue

/**
 * Demonstration test for vector code search functionality.
 *
 * This test shows how the vector search works end-to-end:
 * 1. Create mock embeddings for code snippets
 * 2. Store them in a simulated index
 * 3. Search for semantically similar code
 * 4. Verify results are ranked by similarity
 */
class VectorSearchDemoTest {

    /**
     * Demo: Semantic search finds related code snippets
     *
     * This test simulates:
     * - 3 code snippets (authentication, utility, unrelated)
     * - A search for "user login validation"
     * - Verification that auth code ranks highest
     */
    @Test
    fun demoSemanticSearchRanking() {
        // Create mock embeddings (in real use, these come from the embedding model)
        // These are simplified 4-dimensional vectors for demonstration

        // Snippet 1: Authentication code
        val authSnippet = floatArrayOf(
            0.8f,  // High "authentication" component
            0.7f,  // High "user" component
            0.1f,  // Low "logging" component
            0.2f   // Low "utility" component
        )

        // Snippet 2: Logging utility
        val loggingSnippet = floatArrayOf(
            0.1f,  // Low "authentication"
            0.2f,  // Low "user"
            0.9f,  // High "logging"
            0.3f   // Low "utility"
        )

        // Snippet 3: Math utility (unrelated)
        val mathSnippet = floatArrayOf(
            0.0f,  // No "authentication"
            0.0f,  // No "user"
            0.0f,  // No "logging"
            0.9f   // High "utility"
        )

        // Search query embedding (for "user login validation")
        val queryEmbedding = floatArrayOf(
            0.8f,  // "authentication"
            0.7f,  // "user"
            0.1f,  // "logging"
            0.0f   // "utility"
        )

        // Calculate similarity scores
        val authSimilarity = VectorMath.cosineSimilarity(queryEmbedding, authSnippet)
        val loggingSimilarity = VectorMath.cosineSimilarity(queryEmbedding, loggingSnippet)
        val mathSimilarity = VectorMath.cosineSimilarity(queryEmbedding, mathSnippet)

        println("\n=== Vector Search Demo ===")
        println("Query: 'user login validation'")
        println("\nResults ranked by similarity:")
        println("1. Auth snippet:    $authSimilarity")
        println("2. Logging snippet: $loggingSimilarity")
        println("3. Math snippet:    $mathSimilarity")

        // Verify ranking: auth > logging > math
        assertTrue(authSimilarity > loggingSimilarity, "Auth snippet should rank higher than logging")
        assertTrue(loggingSimilarity > mathSimilarity, "Logging should rank higher than math")
        assertTrue(authSimilarity > 0.7f, "Auth snippet should have high similarity")
        assertTrue(mathSimilarity < 0.3f, "Math snippet should have low similarity")
    }

    /**
     * Demo: Show how cosine similarity works with real-world code contexts
     */
    @Test
    fun demoCodeSimilarityCalculation() {
        // Simulating embeddings for real Kotlin code snippets
        // (These would come from the all-MiniLM-L6-v2 embedding model in production)

        // Code 1: Event listener pattern
        val eventListenerCode = floatArrayOf(0.92f, 0.15f, 0.08f, 0.03f)

        // Code 2: Click handler pattern (semantically similar)
        val clickHandlerCode = floatArrayOf(0.88f, 0.18f, 0.10f, 0.05f)

        // Code 3: Database query (different context)
        val databaseCode = floatArrayOf(0.05f, 0.88f, 0.70f, 0.20f)

        val similarity1 = VectorMath.cosineSimilarity(eventListenerCode, clickHandlerCode)
        val similarity2 = VectorMath.cosineSimilarity(eventListenerCode, databaseCode)

        println("\n=== Code Pattern Similarity ===")
        println("Event Listener vs Click Handler: $similarity1 (should be high ~0.99)")
        println("Event Listener vs Database Query: $similarity2 (should be low ~0.15)")

        // Event listeners and click handlers are semantically similar
        assertTrue(similarity1 > 0.95f, "Similar patterns should have high similarity")

        // Event listeners and database queries are different
        assertTrue(similarity2 < 0.30f, "Different patterns should have low similarity")
    }

    /**
     * Demo: Show search result ordering
     */
    @Test
    fun demoSearchResultOrdering() {
        // Simulated code snippets in index
        val snippets = listOf(
            Pair("Authentication.kt", floatArrayOf(0.95f, 0.05f, 0.02f)),
            Pair("Logger.kt", floatArrayOf(0.10f, 0.92f, 0.05f)),
            Pair("LoginActivity.kt", floatArrayOf(0.92f, 0.08f, 0.03f)),
            Pair("Utils.kt", floatArrayOf(0.15f, 0.20f, 0.85f)),
            Pair("AuthValidator.kt", floatArrayOf(0.98f, 0.02f, 0.01f))
        )

        val queryEmbedding = floatArrayOf(0.95f, 0.08f, 0.03f)

        // Calculate similarities and rank
        val results = snippets
            .map { (filename, embedding) ->
                filename to VectorMath.cosineSimilarity(queryEmbedding, embedding)
            }
            .sortedByDescending { it.second }

        println("\n=== Search Results for 'authentication'===")
        results.forEachIndexed { index, (filename, similarity) ->
            println("${index + 1}. $filename (similarity: ${"%.4f".format(similarity)})")
        }

        // Verify ranking - check that results are sorted by similarity (descending)
        for (i in 0 until results.size - 1) {
            assertTrue(
                results[i].second >= results[i + 1].second,
                "Results should be sorted by similarity descending"
            )
        }
    }

    /**
     * Demo: Show why vector search is better than keyword matching
     */
    @Test
    fun demoSemanticSearchVsKeyword() {
        println("\n=== Vector Search vs Keyword Search ===\n")

        // Scenario: User searches for "password validation"
        val queryEmbedding = floatArrayOf(
            0.85f,  // security/auth component
            0.80f,  // validation component
            0.10f,  // data structure
            0.05f   // utility
        )

        // Snippet A: Actual password validator
        val passwordValidator = floatArrayOf(0.90f, 0.88f, 0.12f, 0.08f)

        // Snippet B: Comment mentioning "password"
        val comment = floatArrayOf(0.20f, 0.15f, 0.80f, 0.70f)  // Very different semantically

        // Snippet C: Login logic (semantically related but no keyword match)
        val loginLogic = floatArrayOf(0.88f, 0.85f, 0.14f, 0.10f)

        val scoreA = VectorMath.cosineSimilarity(queryEmbedding, passwordValidator)
        val scoreB = VectorMath.cosineSimilarity(queryEmbedding, comment)
        val scoreC = VectorMath.cosineSimilarity(queryEmbedding, loginLogic)

        println("Query: 'password validation'")
        println("\nKeyword Search would find:")
        println("  - Comment containing 'password' ❌ (Not actually about validation)")
        println("\nVector Search finds:")
        println("  1. Password Validator (score: ${"%.4f".format(scoreA)}) ✅ Most relevant")
        println("  2. Login Logic (score: ${"%.4f".format(scoreC)}) ✅ Semantically related")
        println("  3. Comment (score: ${"%.4f".format(scoreB)}) ⬇️ Lower ranking despite keyword")

        assertTrue(scoreA > scoreB, "Semantic meaning should rank higher than keyword presence")
        assertTrue(scoreC > scoreB, "Related logic should rank higher than unrelated mention")
    }
}
