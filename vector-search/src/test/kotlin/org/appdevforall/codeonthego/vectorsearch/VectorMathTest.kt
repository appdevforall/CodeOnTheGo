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

/**
 * Unit tests for VectorMath cosine similarity calculation.
 *
 * Tests the core vector mathematics functionality used for semantic similarity.
 */
class VectorMathTest {

    @Test
    fun testIdenticalVectors() {
        val vector = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val similarity = VectorMath.cosineSimilarity(vector, vector)
        assertEquals(1.0, similarity.toDouble(), 0.0001)
    }

    @Test
    fun testOrthogonalVectors() {
        // Orthogonal vectors have a dot product of 0
        val vector1 = floatArrayOf(1f, 0f, 0f)
        val vector2 = floatArrayOf(0f, 1f, 0f)
        val similarity = VectorMath.cosineSimilarity(vector1, vector2)
        assertEquals(0.0, similarity.toDouble(), 0.0001)
    }

    @Test
    fun testOppositeVectors() {
        // Opposite vectors should have similarity of -1.0
        val vector1 = floatArrayOf(1f, 2f, 3f)
        val vector2 = floatArrayOf(-1f, -2f, -3f)
        val similarity = VectorMath.cosineSimilarity(vector1, vector2)
        assertEquals(-1.0, similarity.toDouble(), 0.0001)
    }

    @Test
    fun testZeroVectors() {
        // When both vectors are zero, should return 0.0 (handle division by zero)
        val zeroVector = floatArrayOf(0f, 0f, 0f)
        val similarity = VectorMath.cosineSimilarity(zeroVector, zeroVector)
        assertEquals(0.0, similarity.toDouble())
    }

    @Test
    fun testZeroVectorWithNonZeroVector() {
        // Zero vector with non-zero vector should return 0.0
        val zeroVector = floatArrayOf(0f, 0f, 0f)
        val nonZeroVector = floatArrayOf(1f, 2f, 3f)
        val similarity = VectorMath.cosineSimilarity(zeroVector, nonZeroVector)
        assertEquals(0.0, similarity.toDouble())
    }

    @Test
    fun testNormalizedVectors() {
        // Two normalized vectors with same direction
        val vector1 = floatArrayOf(1f / 1.732f, 1f / 1.732f, 1f / 1.732f)
        val vector2 = floatArrayOf(1f / 1.732f, 1f / 1.732f, 1f / 1.732f)
        val similarity = VectorMath.cosineSimilarity(vector1, vector2)
        assertEquals(1.0, similarity.toDouble(), 0.0001)
    }

    @Test
    fun testPartialOverlapVectors() {
        // Vectors with partial overlap
        val vector1 = floatArrayOf(1f, 1f, 0f)
        val vector2 = floatArrayOf(1f, 0f, 0f)
        val similarity = VectorMath.cosineSimilarity(vector1, vector2)
        // Similarity should be positive but less than 1
        assertTrue(similarity > 0f && similarity < 1f)
    }

    @Test
    fun testLargeVectors() {
        // Test with larger dimensional vectors (typical embedding dimension)
        val vector1 = FloatArray(768) { i -> (i + 1).toFloat() }
        val vector2 = FloatArray(768) { i -> (i + 1).toFloat() }
        val similarity = VectorMath.cosineSimilarity(vector1, vector2)
        assertEquals(1.0, similarity.toDouble(), 0.0001)
    }

    @Test
    fun testSingleElementVectors() {
        val vector1 = floatArrayOf(5f)
        val vector2 = floatArrayOf(5f)
        val similarity = VectorMath.cosineSimilarity(vector1, vector2)
        assertEquals(1.0, similarity.toDouble(), 0.0001)
    }

    @Test
    fun testNegativeComponents() {
        // Test with negative components
        val vector1 = floatArrayOf(-1f, -2f, -3f)
        val vector2 = floatArrayOf(-1f, -2f, -3f)
        val similarity = VectorMath.cosineSimilarity(vector1, vector2)
        assertEquals(1.0, similarity.toDouble(), 0.0001)
    }

    @Test
    fun testMixedPositiveNegative() {
        // Test with mixed positive and negative
        val vector1 = floatArrayOf(1f, -2f, 3f)
        val vector2 = floatArrayOf(1f, -2f, 3f)
        val similarity = VectorMath.cosineSimilarity(vector1, vector2)
        assertEquals(1.0, similarity.toDouble(), 0.0001)
    }

    @Test
    fun testSmallVectorDifference() {
        // Vectors that are slightly different
        val vector1 = floatArrayOf(1f, 2f, 3f)
        val vector2 = floatArrayOf(1.01f, 2.01f, 3.01f)
        val similarity = VectorMath.cosineSimilarity(vector1, vector2)
        assertTrue(similarity > 0.99f && similarity < 1.0f)
    }

    @Test
    fun testSymmetry() {
        // Cosine similarity should be symmetric: sim(a, b) == sim(b, a)
        val vector1 = floatArrayOf(1f, 2f, 3f)
        val vector2 = floatArrayOf(4f, 5f, 6f)
        val sim1 = VectorMath.cosineSimilarity(vector1, vector2)
        val sim2 = VectorMath.cosineSimilarity(vector2, vector1)
        assertEquals(sim1.toDouble(), sim2.toDouble(), 0.0001)
    }

    @Test
    fun testVerySmallNonZeroVectors() {
        val vector1 = floatArrayOf(0.001f, 0.002f, 0.003f)
        val vector2 = floatArrayOf(0.001f, 0.002f, 0.003f)
        val similarity = VectorMath.cosineSimilarity(vector1, vector2)
        assertEquals(1.0, similarity.toDouble(), 0.0001)
    }

    @Test
    fun testZeroDimensionHandling() {
        // Edge case: single component vectors that are zero on both
        val vector1 = floatArrayOf(0f)
        val vector2 = floatArrayOf(0f)
        val similarity = VectorMath.cosineSimilarity(vector1, vector2)
        assertEquals(0.0, similarity.toDouble())
    }

    @Test
    fun testRealisticEmbeddingExample() {
        // Simulate realistic word embeddings
        // Example: [hello] vs [hello] should be identical
        val helloEmbedding = floatArrayOf(0.1f, 0.2f, 0.15f, 0.3f, 0.25f)
        val helloEmbedding2 = floatArrayOf(0.1f, 0.2f, 0.15f, 0.3f, 0.25f)
        val similarity = VectorMath.cosineSimilarity(helloEmbedding, helloEmbedding2)
        assertEquals(1.0, similarity.toDouble(), 0.0001)
    }

    @Test
    fun testSimilarButDifferentEmbeddings() {
        // Example: [function] vs [method] - similar but not identical
        val functionEmbedding = floatArrayOf(0.5f, 0.3f, 0.2f, 0.4f, 0.1f)
        val methodEmbedding = floatArrayOf(0.51f, 0.32f, 0.21f, 0.41f, 0.11f)
        val similarity = VectorMath.cosineSimilarity(functionEmbedding, methodEmbedding)
        assertTrue(similarity > 0.98f && similarity < 1.0f)
    }
}
