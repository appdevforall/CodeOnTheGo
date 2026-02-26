package org.appdevforall.codeonthego.lsp.kotlin.server

import java.util.concurrent.ConcurrentHashMap

/**
 * Manages open documents and their analysis state.
 *
 * DocumentManager tracks all documents currently open in the editor,
 * providing efficient lookup and update operations.
 *
 * ## Thread Safety
 *
 * All operations are thread-safe. Document states can be accessed
 * concurrently from multiple analysis threads.
 *
 * ## Usage
 *
 * ```kotlin
 * val manager = DocumentManager()
 *
 * // Open a document
 * manager.open("file:///src/Main.kt", "fun main() {}", 1)
 *
 * // Update content
 * manager.update("file:///src/Main.kt", "fun main() { println() }", 2)
 *
 * // Get state
 * val state = manager.get("file:///src/Main.kt")
 *
 * // Close document
 * manager.close("file:///src/Main.kt")
 * ```
 */
class DocumentManager {

    private val documents = ConcurrentHashMap<String, DocumentState>()

    val openDocumentCount: Int get() = documents.size

    val openDocuments: Collection<DocumentState> get() = documents.values.toList()

    val openUris: Set<String> get() = documents.keys.toSet()

    /**
     * Opens a new document.
     *
     * @param uri Document URI
     * @param content Initial content
     * @param version Initial version
     * @return The created document state
     */
    fun open(uri: String, content: String, version: Int): DocumentState {
        val state = DocumentState(uri, version, content)
        documents[uri] = state
        return state
    }

    /**
     * Closes a document.
     *
     * @param uri Document URI
     * @return The closed document state, or null if not found
     */
    fun close(uri: String): DocumentState? {
        return documents.remove(uri)
    }

    /**
     * Gets a document by URI.
     *
     * @param uri Document URI
     * @return The document state, or null if not open
     */
    fun get(uri: String): DocumentState? {
        return documents[uri]
    }

    /**
     * Gets a document by URI, throwing if not found.
     */
    fun getOrThrow(uri: String): DocumentState {
        return documents[uri] ?: throw DocumentNotFoundException(uri)
    }

    /**
     * Checks if a document is open.
     */
    fun isOpen(uri: String): Boolean {
        return documents.containsKey(uri)
    }

    /**
     * Updates document content (full sync).
     *
     * @param uri Document URI
     * @param content New content
     * @param version New version
     * @return The updated document state
     */
    fun update(uri: String, content: String, version: Int): DocumentState {
        val state = documents[uri] ?: throw DocumentNotFoundException(uri)
        state.updateContent(content, version)
        return state
    }

    /**
     * Applies an incremental change to a document.
     *
     * @param uri Document URI
     * @param startLine Start line (0-based)
     * @param startChar Start character (0-based)
     * @param endLine End line (0-based)
     * @param endChar End character (0-based)
     * @param newText Replacement text
     * @param version New version
     * @return The updated document state
     */
    fun applyChange(
        uri: String,
        startLine: Int,
        startChar: Int,
        endLine: Int,
        endChar: Int,
        newText: String,
        version: Int
    ): DocumentState {
        val state = documents[uri] ?: throw DocumentNotFoundException(uri)
        state.applyChange(startLine, startChar, endLine, endChar, newText, version)
        return state
    }

    /**
     * Applies an incremental change using direct character indices.
     * This bypasses line/column to offset conversion for more reliable sync.
     *
     * @param uri Document URI
     * @param startIndex Start character index (0-based)
     * @param endIndex End character index (0-based)
     * @param newText Replacement text
     * @param version New version
     * @return The updated document state
     */
    fun applyChangeByIndex(
        uri: String,
        startIndex: Int,
        endIndex: Int,
        newText: String,
        version: Int
    ): DocumentState {
        val state = documents[uri] ?: throw DocumentNotFoundException(uri)
        state.applyChangeByIndex(startIndex, endIndex, newText, version)
        return state
    }

    /**
     * Gets all documents that need parsing.
     */
    fun getUnparsedDocuments(): List<DocumentState> {
        return documents.values.filter { !it.isParsed }
    }

    /**
     * Gets all documents that need analysis.
     */
    fun getUnanalyzedDocuments(): List<DocumentState> {
        return documents.values.filter { it.isParsed && !it.isAnalyzed }
    }

    /**
     * Gets all documents in a package.
     */
    fun getDocumentsInPackage(packageName: String): List<DocumentState> {
        return documents.values.filter { it.packageName == packageName }
    }

    /**
     * Gets all documents with errors.
     */
    fun getDocumentsWithErrors(): List<DocumentState> {
        return documents.values.filter { it.hasErrors }
    }

    /**
     * Gets all analyzed documents.
     */
    fun getAnalyzedDocuments(): List<DocumentState> {
        return documents.values.filter { it.isAnalyzed }
    }

    /**
     * Finds document by file path.
     */
    fun findByPath(path: String): DocumentState? {
        return documents.values.find { it.filePath == path }
    }

    /**
     * Gets the document containing a position.
     */
    fun getAtPosition(uri: String, line: Int, character: Int): DocumentState? {
        val state = documents[uri] ?: return null
        if (line < 0 || line >= state.lineCount) return null
        return state
    }

    /**
     * Invalidates all documents (forces re-analysis).
     */
    fun invalidateAll() {
        documents.values.forEach { it.invalidate() }
    }

    /**
     * Invalidates documents in a package.
     */
    fun invalidatePackage(packageName: String) {
        documents.values
            .filter { it.packageName == packageName }
            .forEach { it.invalidate() }
    }

    /**
     * Clears all documents.
     */
    fun clear() {
        documents.clear()
    }

    /**
     * Gets statistics about open documents.
     */
    fun getStatistics(): DocumentStatistics {
        val docs = documents.values.toList()
        return DocumentStatistics(
            totalDocuments = docs.size,
            parsedDocuments = docs.count { it.isParsed },
            analyzedDocuments = docs.count { it.isAnalyzed },
            documentsWithErrors = docs.count { it.hasErrors },
            totalLines = docs.sumOf { it.lineCount },
            totalCharacters = docs.sumOf { it.content.length }
        )
    }

    override fun toString(): String {
        return "DocumentManager(documents=${documents.size})"
    }
}

/**
 * Statistics about open documents.
 */
data class DocumentStatistics(
    val totalDocuments: Int,
    val parsedDocuments: Int,
    val analyzedDocuments: Int,
    val documentsWithErrors: Int,
    val totalLines: Int,
    val totalCharacters: Int
)

/**
 * Exception thrown when a document is not found.
 */
class DocumentNotFoundException(uri: String) : Exception("Document not found: $uri")
