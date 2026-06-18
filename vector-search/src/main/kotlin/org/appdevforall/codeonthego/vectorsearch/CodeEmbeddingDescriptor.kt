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

import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [IndexDescriptor] for [CodeEmbedding].
 *
 * Queryable fields:
 * - `filePath`: prefix-searchable, for file-based filtering and search
 * - `language`: exact, for language-specific filtering (kotlin, java, xml, etc.)
 * - `chunkIndex`: exact, for chunk ordering and deduplication
 *
 * Blob serialization uses ByteBuffer with LITTLE_ENDIAN for embedding FloatArray storage.
 */
object CodeEmbeddingDescriptor : IndexDescriptor<CodeEmbedding> {

    const val KEY_FILE_PATH = "filePath"
    const val KEY_LANGUAGE = "language"
    const val KEY_CHUNK_INDEX = "chunkIndex"

    override val name: String = "code_embeddings"

    override val fields: List<IndexField> = listOf(
        IndexField(name = KEY_FILE_PATH, prefixSearchable = true),
        IndexField(name = KEY_LANGUAGE),
        IndexField(name = KEY_CHUNK_INDEX),
    )

    override fun fieldValues(entry: CodeEmbedding): Map<String, String?> = mapOf(
        KEY_FILE_PATH to entry.filePath,
        KEY_LANGUAGE to entry.language,
        KEY_CHUNK_INDEX to entry.chunkIndex.toString(),
    )

    override fun serialize(entry: CodeEmbedding): ByteArray {
        // Calculate size: key, sourceId, filePath, chunkText, language, chunkIndex, startLine, endLine
        // For floats, we store the array length + the floats themselves

        val keyBytes = entry.key.toByteArray(Charsets.UTF_8)
        val sourceIdBytes = entry.sourceId.toByteArray(Charsets.UTF_8)
        val filePathBytes = entry.filePath.toByteArray(Charsets.UTF_8)
        val chunkTextBytes = entry.chunkText.toByteArray(Charsets.UTF_8)
        val languageBytes = entry.language.toByteArray(Charsets.UTF_8)

        // Calculate total size
        val totalSize = 4 +  // key length
            keyBytes.size +
            4 +  // sourceId length
            sourceIdBytes.size +
            4 +  // filePath length
            filePathBytes.size +
            4 +  // chunkText length
            chunkTextBytes.size +
            4 +  // language length
            languageBytes.size +
            4 +  // chunkIndex
            4 +  // startLine
            4 +  // endLine
            4 +  // embedding array length
            (entry.embedding.size * 4)  // embedding floats

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Write strings with length prefix
        buffer.putInt(keyBytes.size)
        buffer.put(keyBytes)

        buffer.putInt(sourceIdBytes.size)
        buffer.put(sourceIdBytes)

        buffer.putInt(filePathBytes.size)
        buffer.put(filePathBytes)

        buffer.putInt(chunkTextBytes.size)
        buffer.put(chunkTextBytes)

        buffer.putInt(languageBytes.size)
        buffer.put(languageBytes)

        // Write integers
        buffer.putInt(entry.chunkIndex)
        buffer.putInt(entry.startLine)
        buffer.putInt(entry.endLine)

        // Write embedding array with length prefix
        buffer.putInt(entry.embedding.size)
        for (float in entry.embedding) {
            buffer.putFloat(float)
        }

        return buffer.array()
    }

    override fun deserialize(bytes: ByteArray): CodeEmbedding {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Read strings with length prefix
        val key = readString(buffer)
        val sourceId = readString(buffer)
        val filePath = readString(buffer)
        val chunkText = readString(buffer)
        val language = readString(buffer)

        // Read integers
        val chunkIndex = buffer.int
        val startLine = buffer.int
        val endLine = buffer.int

        // Read embedding array
        val embeddingLength = buffer.int
        val embedding = FloatArray(embeddingLength)
        for (i in 0 until embeddingLength) {
            embedding[i] = buffer.float
        }

        return CodeEmbedding(
            key = key,
            sourceId = sourceId,
            filePath = filePath,
            chunkText = chunkText,
            language = language,
            chunkIndex = chunkIndex,
            startLine = startLine,
            endLine = endLine,
            embedding = embedding,
        )
    }

    private fun readString(buffer: ByteBuffer): String {
        val length = buffer.int
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
