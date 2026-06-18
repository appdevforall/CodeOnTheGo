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

import kotlin.math.sqrt

/**
 * Utility object for vector mathematics operations.
 * Provides functions for comparing and analyzing embeddings.
 */
object VectorMath {

    /**
     * Calculates the cosine similarity between two vectors.
     *
     * Cosine similarity is the standard metric for comparing embeddings.
     * It measures the cosine of the angle between two vectors in a multi-dimensional space.
     *
     * Algorithm:
     * 1. Calculate dot product: sum of a[i] * b[i]
     * 2. Calculate norm for vector a: sqrt(sum of a[i]²)
     * 3. Calculate norm for vector b: sqrt(sum of b[i]²)
     * 4. Return dotProduct / (normA * normB)
     * 5. Handle zero-division: return 0.0f if denominator is 0
     *
     * @param a First embedding vector
     * @param b Second embedding vector
     * @return Float between -1.0 and 1.0 (or 0.0 if either vector has zero magnitude)
     *         - 1.0 indicates identical direction (maximum similarity)
     *         - 0.0 indicates orthogonal vectors (no similarity)
     *         - -1.0 indicates opposite direction (inverse similarity)
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dotProduct = 0.0f
        var normASquared = 0.0f
        var normBSquared = 0.0f

        // Single-pass calculation for efficiency
        for (i in a.indices) {
            val aVal = a[i]
            val bVal = b[i]

            dotProduct += aVal * bVal
            normASquared += aVal * aVal
            normBSquared += bVal * bVal
        }

        val normA = sqrt(normASquared)
        val normB = sqrt(normBSquared)
        val denominator = normA * normB

        // Handle zero-division case
        return if (denominator == 0.0f) {
            0.0f
        } else {
            dotProduct / denominator
        }
    }
}
