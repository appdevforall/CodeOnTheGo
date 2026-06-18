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

import org.appdevforall.codeonthego.indexing.api.Indexable

/**
 * Represents a code embedding (semantic vector) stored in the database.
 *
 * Each CodeEmbedding corresponds to a chunk of source code from a file.
 * The embedding is a 384-dimensional vector computed from the code text,
 * enabling semantic search and similarity comparisons.
 *
 * @property key Unique identifier in format "{filePath}:{chunkIndex}"
 * @property sourceId The file path (for bulk operations when source changes)
 * @property filePath Full path to the source file
 * @property chunkText The actual code text for this chunk
 * @property language Programming language (kotlin, java, or xml)
 * @property chunkIndex Which chunk of the file this is (0-indexed)
 * @property startLine Line number where chunk starts (1-indexed)
 * @property endLine Line number where chunk ends (inclusive, 1-indexed)
 * @property embedding The 384-dimensional embedding vector
 */
data class CodeEmbedding(
    override val key: String,
    override val sourceId: String,
    val filePath: String,
    val chunkText: String,
    val language: String,
    val chunkIndex: Int,
    val startLine: Int,
    val endLine: Int,
    val embedding: FloatArray,
) : Indexable {

    /**
     * Custom equality check that includes floating-point array comparison.
     * Uses contentEquals for FloatArray instead of identity comparison.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CodeEmbedding

        if (key != other.key) return false
        if (sourceId != other.sourceId) return false
        if (filePath != other.filePath) return false
        if (chunkText != other.chunkText) return false
        if (language != other.language) return false
        if (chunkIndex != other.chunkIndex) return false
        if (startLine != other.startLine) return false
        if (endLine != other.endLine) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    /**
     * Custom hash code that includes the embedding array.
     */
    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + sourceId.hashCode()
        result = 31 * result + filePath.hashCode()
        result = 31 * result + chunkText.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + startLine
        result = 31 * result + endLine
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
