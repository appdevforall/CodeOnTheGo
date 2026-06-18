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

import android.content.Context
import com.itsaky.androidide.llamacpp.api.ILlamaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.indexing.SQLiteIndex
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import org.appdevforall.codeonthego.indexing.service.IndexingService
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Well-known key for the code embedding index.
 *
 * Services and UI components use this key to retrieve the
 * code embedding index from the [IndexRegistry].
 */
val CODE_EMBEDDING_INDEX = IndexKey<SQLiteIndex<CodeEmbedding>>("code-embeddings")

/**
 * [IndexingService] that indexes source code files by generating embeddings
 * for semantic chunks of code.
 *
 * For each supported file type (.kt, .java, .xml, .py, .js, .ts), this service:
 * 1. Chunks the file into semantic pieces using [CodeChunker]
 * 2. Generates embeddings for each chunk using the LLM
 * 3. Stores embeddings in the [CodeEmbedding] index
 *
 * Thread safety: all methods are called from the
 * [IndexingServiceManager][org.appdevforall.codeonthego.indexing.service.IndexingServiceManager]'s
 * coroutine scope.
 *
 * @param context Android context for accessing cache directory and resources
 * @param llamaController The LLM controller for generating embeddings
 */
class EmbeddingIndexingService(
    private val context: Context,
    private val llamaController: AtomicReference<ILlamaController?>,
) : IndexingService {

    companion object {
        const val ID = "embedding-indexing-service"
        private val log = LoggerFactory.getLogger(EmbeddingIndexingService::class.java)

        // Supported file extensions and their language names
        private val SUPPORTED_EXTENSIONS = mapOf(
            "kt" to "kotlin",
            "java" to "java",
            "xml" to "xml",
            "py" to "python",
            "js" to "javascript",
            "ts" to "typescript",
        )

        // Directories to skip during indexing
        private val SKIP_DIRECTORIES = setOf(
            ".git",
            "build",
            ".gradle",
            "node_modules",
            "dist",
            "out",
            "target",
        )

        // Embedding dimension (from LLM model)
        private const val EMBEDDING_DIMENSION = 384
    }

    override val id = ID
    override val providedKeys = listOf(CODE_EMBEDDING_INDEX)

    private var embeddingIndex: SQLiteIndex<CodeEmbedding>? = null

    override suspend fun initialize(registry: IndexRegistry) {
        val index = withContext(Dispatchers.IO) {
            val dbPath = context.cacheDir.resolve("embeddings.db")
            SQLiteIndex(
                descriptor = CodeEmbeddingDescriptor,
                context = context,
                dbName = dbPath.absolutePath,
                name = "sqlite:${CodeEmbeddingDescriptor.name}",
            )
        }

        this.embeddingIndex = index
        registry.register(CODE_EMBEDDING_INDEX, index)
        log.info("Code embedding index initialized at {}", context.cacheDir.resolve("embeddings.db"))
    }

    /**
     * Indexes a source directory recursively, generating embeddings for all
     * supported code files.
     *
     * Skips hidden files and certain directories (build, .gradle, etc.).
     * Files that fail to index are logged but do not stop the process.
     *
     * @param directoryPath Path to the directory to index
     */
    suspend fun indexDirectory(directoryPath: String) {
        val index = embeddingIndex ?: run {
            log.warn("Cannot index directory. Embedding index not initialized.")
            return
        }

        val controller = llamaController.get() ?: run {
            log.warn("Cannot index directory. LLM controller not available.")
            return
        }

        withContext(Dispatchers.IO) {
            val rootDir = File(directoryPath)
            if (!rootDir.isDirectory) {
                log.warn("Path is not a directory: {}", directoryPath)
                return@withContext
            }

            indexDirectoryRecursive(rootDir, index, controller)
        }
    }

    /**
     * Recursively indexes files in a directory.
     */
    private suspend fun indexDirectoryRecursive(
        directory: File,
        index: SQLiteIndex<CodeEmbedding>,
        controller: ILlamaController,
    ) {
        try {
            val files = directory.listFiles() ?: return

            for (file in files) {
                // Skip hidden files and skip directories
                if (file.name.startsWith(".")) {
                    continue
                }

                if (file.isDirectory) {
                    if (!SKIP_DIRECTORIES.contains(file.name)) {
                        indexDirectoryRecursive(file, index, controller)
                    }
                    continue
                }

                // Check if file extension is supported
                val extension = file.extension.lowercase()
                val language = SUPPORTED_EXTENSIONS[extension] ?: continue

                try {
                    indexFile(file, language, index, controller)
                } catch (e: Exception) {
                    log.debug("Failed to index file {}: {}", file.absolutePath, e.message)
                }
            }
        } catch (e: Exception) {
            log.debug("Error indexing directory {}: {}", directory.absolutePath, e.message)
        }
    }

    /**
     * Indexes a single file by chunking it and generating embeddings.
     *
     * @param file The file to index
     * @param language The programming language of the file
     * @param index The embedding index to store results in
     * @param controller The LLM controller for generating embeddings
     */
    private suspend fun indexFile(
        file: File,
        language: String,
        index: SQLiteIndex<CodeEmbedding>,
        controller: ILlamaController,
    ) {
        log.debug("Indexing {}", file.absolutePath)

        // Chunk the file
        val chunks = try {
            CodeChunker.chunkFile(file)
        } catch (e: Exception) {
            log.debug("Failed to chunk file {}: {}", file.absolutePath, e.message)
            return
        }

        if (chunks.isEmpty()) {
            log.debug("No chunks generated for file {}", file.absolutePath)
            return
        }

        // Generate embeddings for each chunk
        val embeddings = mutableListOf<CodeEmbedding>()

        for ((chunkIndex, chunk) in chunks.withIndex()) {
            try {
                // Generate embedding using LLM
                val embeddingVector = withContext(Dispatchers.Default) {
                    controller.generateEmbedding(chunk.content)
                }

                if (embeddingVector == null || embeddingVector.isEmpty()) {
                    log.debug(
                        "Failed to generate embedding for chunk {} in file {}",
                        chunkIndex,
                        file.absolutePath
                    )
                    continue
                }

                // Create CodeEmbedding
                // Convert 0-indexed line numbers to 1-indexed for storage
                val embedding = CodeEmbedding(
                    key = "${file.absolutePath}:$chunkIndex",
                    sourceId = file.absolutePath,
                    filePath = file.absolutePath,
                    chunkText = chunk.content,
                    language = language,
                    chunkIndex = chunkIndex,
                    startLine = chunk.startLine + 1,  // Convert to 1-indexed
                    endLine = chunk.endLine + 1,      // Convert to 1-indexed
                    embedding = embeddingVector,
                )

                embeddings.add(embedding)
            } catch (e: Exception) {
                log.debug(
                    "Failed to generate embedding for chunk {} in file {}: {}",
                    chunkIndex,
                    file.absolutePath,
                    e.message
                )
            }
        }

        // Store embeddings in index
        if (embeddings.isNotEmpty()) {
            try {
                index.insertAll(embeddings.asSequence())
                log.debug(
                    "Indexed {} embeddings for file {}",
                    embeddings.size,
                    file.absolutePath
                )
            } catch (e: Exception) {
                log.debug("Failed to store embeddings for file {}: {}", file.absolutePath, e.message)
            }
        }
    }

    /**
     * Removes all embeddings for a specific source file.
     *
     * @param filePath Path to the file whose embeddings should be removed
     */
    suspend fun removeFileEmbeddings(filePath: String) {
        val index = embeddingIndex ?: run {
            log.warn("Cannot remove embeddings. Index not initialized.")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                index.removeBySource(filePath)
                log.debug("Removed embeddings for file {}", filePath)
            } catch (e: Exception) {
                log.warn("Failed to remove embeddings for file {}: {}", filePath, e.message)
            }
        }
    }

    override fun close() {
        embeddingIndex?.close()
        log.info("Code embedding index closed")
    }
}
