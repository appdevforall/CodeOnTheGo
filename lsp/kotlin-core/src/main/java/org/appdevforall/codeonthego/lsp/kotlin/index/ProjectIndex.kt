package org.appdevforall.codeonthego.lsp.kotlin.index

import java.util.concurrent.ConcurrentHashMap

/**
 * Project-wide symbol index aggregating all file indexes.
 *
 * ProjectIndex provides unified symbol lookup across:
 * - All indexed project source files
 * - Standard library symbols (via StdlibIndex)
 * - Dependencies (if indexed)
 *
 * ## Thread Safety
 *
 * ProjectIndex is thread-safe for concurrent read/write operations.
 * File indexes can be updated while queries are being processed.
 *
 * ## Usage
 *
 * ```kotlin
 * val projectIndex = ProjectIndex()
 * projectIndex.updateFile(fileIndex)
 *
 * val stringClass = projectIndex.findByFqName("kotlin.String")
 * val completions = projectIndex.findByPrefix("str", limit = 50)
 * ```
 */
class ProjectIndex : SymbolIndex {

    private val fileIndexes = ConcurrentHashMap<String, FileIndex>()
    private val packageIndex = PackageIndex()
    private val extensionIndex = ExtensionIndex()

    @Volatile
    private var stdlibIndex: StdlibIndex? = null

    @Volatile
    private var classpathIndex: ClasspathIndex? = null

    override val size: Int
        get() = fileIndexes.values.sumOf { it.size } + (stdlibIndex?.size ?: 0) + (classpathIndex?.size ?: 0)

    val fileCount: Int get() = fileIndexes.size

    val packageNames: Set<String>
        get() = packageIndex.packageNames + (stdlibIndex?.packageNames ?: emptySet()) + (classpathIndex?.packageNames ?: emptySet())

    override fun findByFqName(fqName: String): IndexedSymbol? {
        for (fileIndex in fileIndexes.values) {
            fileIndex.findByFqName(fqName)?.let { return it }
        }

        stdlibIndex?.findByFqName(fqName)?.let { return it }

        return classpathIndex?.findByFqName(fqName)
    }

    override fun findBySimpleName(name: String): List<IndexedSymbol> {
        val results = mutableListOf<IndexedSymbol>()

        for (fileIndex in fileIndexes.values) {
            results.addAll(fileIndex.findBySimpleName(name))
        }

        stdlibIndex?.findBySimpleName(name)?.let { results.addAll(it) }
        classpathIndex?.findBySimpleName(name)?.let { results.addAll(it) }

        return results.distinctBy { it.fqName }
    }

    fun findInProjectFiles(name: String): List<IndexedSymbol> {
        val results = mutableListOf<IndexedSymbol>()
        for (fgiveileIndex in fileIndexes.values) {
            results.addAll(fileIndex.findBySimpleName(name))
        }
        return results
    }

    override fun findByPackage(packageName: String): List<IndexedSymbol> {
        val results = mutableListOf<IndexedSymbol>()

        results.addAll(packageIndex.findByPackage(packageName))
        stdlibIndex?.findByPackage(packageName)?.let { results.addAll(it) }
        classpathIndex?.findByPackage(packageName)?.let { results.addAll(it) }

        return results.distinctBy { it.fqName }
    }

    override fun findByPrefix(prefix: String, limit: Int): List<IndexedSymbol> {
        val results = mutableListOf<IndexedSymbol>()

        for (fileIndex in fileIndexes.values) {
            results.addAll(fileIndex.findByPrefix(prefix, 0))
        }

        stdlibIndex?.findByPrefix(prefix, 0)?.let { results.addAll(it) }
        classpathIndex?.findByPrefix(prefix, 0)?.let { results.addAll(it) }

        val distinct = results.distinctBy { it.fqName }
        return if (limit > 0) distinct.take(limit) else distinct
    }

    override fun getAllClasses(): List<IndexedSymbol> {
        val results = mutableListOf<IndexedSymbol>()

        for (fileIndex in fileIndexes.values) {
            results.addAll(fileIndex.getAllClasses())
        }

        stdlibIndex?.getAllClasses()?.let { results.addAll(it) }
        classpathIndex?.getAllClasses()?.let { results.addAll(it) }

        return results.distinctBy { it.fqName }
    }

    override fun getAllFunctions(): List<IndexedSymbol> {
        val results = mutableListOf<IndexedSymbol>()

        for (fileIndex in fileIndexes.values) {
            results.addAll(fileIndex.getAllFunctions())
        }

        stdlibIndex?.getAllFunctions()?.let { results.addAll(it) }
        classpathIndex?.getAllFunctions()?.let { results.addAll(it) }

        return results.distinctBy { it.fqName }
    }

    override fun getAllProperties(): List<IndexedSymbol> {
        val results = mutableListOf<IndexedSymbol>()

        for (fileIndex in fileIndexes.values) {
            results.addAll(fileIndex.getAllProperties())
        }

        stdlibIndex?.getAllProperties()?.let { results.addAll(it) }
        classpathIndex?.getAllProperties()?.let { results.addAll(it) }

        return results.distinctBy { it.fqName }
    }

    /**
     * Updates or adds a file index.
     *
     * @param fileIndex The file index to add/update
     */
    fun updateFile(fileIndex: FileIndex) {
        val oldIndex = fileIndexes.put(fileIndex.filePath, fileIndex)

        if (oldIndex != null) {
            removeFromPackageIndex(oldIndex)
            removeFromExtensionIndex(oldIndex)
        }

        addToPackageIndex(fileIndex)
        addToExtensionIndex(fileIndex)
    }

    /**
     * Removes a file from the index.
     *
     * @param filePath Path of the file to remove
     * @return true if the file was indexed and removed
     */
    fun removeFile(filePath: String): Boolean {
        val fileIndex = fileIndexes.remove(filePath) ?: return false

        removeFromPackageIndex(fileIndex)
        removeFromExtensionIndex(fileIndex)

        return true
    }

    /**
     * Gets a file index by path.
     */
    fun getFileIndex(filePath: String): FileIndex? {
        return fileIndexes[filePath]
    }

    /**
     * Gets all indexed file paths.
     */
    fun getIndexedFiles(): Set<String> {
        return fileIndexes.keys.toSet()
    }

    /**
     * Sets the stdlib index.
     */
    fun setStdlibIndex(index: StdlibIndex) {
        stdlibIndex = index
    }

    /**
     * Gets the stdlib index if loaded.
     */
    fun getStdlibIndex(): StdlibIndex? {
        return stdlibIndex
    }

    /**
     * Sets the classpath index.
     */
    fun setClasspathIndex(index: ClasspathIndex) {
        classpathIndex = index
    }

    /**
     * Gets the classpath index if loaded.
     */
    fun getClasspathIndex(): ClasspathIndex? {
        return classpathIndex
    }

    /**
     * Finds extensions applicable to a receiver type.
     *
     * @param receiverType The receiver type name
     * @param supertypes Optional supertypes to include
     * @param includeAnyExtensions Whether to include extensions on Any/Any?
     * @return All applicable extensions from project and stdlib
     */
    fun findExtensions(
        receiverType: String,
        supertypes: List<String> = emptyList(),
        includeAnyExtensions: Boolean = false
    ): List<IndexedSymbol> {
        val results = mutableListOf<IndexedSymbol>()

        results.addAll(extensionIndex.findFor(receiverType, supertypes, includeAnyExtensions))
        stdlibIndex?.findExtensions(receiverType, supertypes, includeAnyExtensions)?.let { results.addAll(it) }

        classpathIndex?.let { cpIndex ->
            results.addAll(cpIndex.findExtensionsFor(receiverType))
            supertypes.forEach { st -> results.addAll(cpIndex.findExtensionsFor(st)) }
        }

        return results.distinctBy { it.fqName }
    }

    /**
     * Finds symbols visible from a given file.
     *
     * Includes:
     * - Symbols in the same file
     * - Symbols in the same package
     * - Public symbols from other packages
     * - Stdlib symbols
     *
     * @param filePath The file path for context
     * @param prefix Optional name prefix filter
     * @param limit Maximum results
     * @return Visible symbols
     */
    fun findVisibleFrom(
        filePath: String,
        prefix: String = "",
        limit: Int = 0
    ): List<IndexedSymbol> {
        val results = mutableListOf<IndexedSymbol>()

        val currentFile = fileIndexes[filePath]
        val currentPackage = currentFile?.packageName ?: ""

        currentFile?.findByPrefix(prefix, 0)?.let { results.addAll(it) }

        if (currentPackage.isNotEmpty()) {
            packageIndex.findByPackage(currentPackage)
                .filter { prefix.isEmpty() || it.name.startsWith(prefix, ignoreCase = true) }
                .filter { it.filePath != filePath }
                .let { results.addAll(it) }
        }

        for (fileIndex in fileIndexes.values) {
            if (fileIndex.filePath == filePath) continue
            if (fileIndex.packageName == currentPackage) continue

            fileIndex.findByPrefix(prefix, 0)
                .filter { it.isPublicApi && it.isTopLevel }
                .let { results.addAll(it) }
        }

        stdlibIndex?.findByPrefix(prefix, 0)
            ?.filter { it.isPublicApi }
            ?.let { results.addAll(it) }

        classpathIndex?.findByPrefix(prefix, 0)
            ?.filter { it.isPublicApi }
            ?.let { results.addAll(it) }

        val distinct = results.distinctBy { it.fqName }
        return if (limit > 0) distinct.take(limit) else distinct
    }

    /**
     * Executes a query against the index.
     *
     * @param query The query parameters
     * @return Matching symbols
     */
    fun query(query: IndexQuery): List<IndexedSymbol> {
        var results = when {
            query.namePrefix != null -> findByPrefix(query.namePrefix, 0)
            query.packageName != null -> findByPackage(query.packageName)
            query.extensionReceiverType != null -> findExtensions(query.extensionReceiverType)
            else -> getAllSymbols()
        }

        if (query.kinds != null) {
            results = results.filter { it.kind in query.kinds }
        }

        if (!query.includeDeprecated) {
            results = results.filter { !it.deprecated }
        }

        if (!query.includeInternal) {
            results = results.filter { it.isPublicApi }
        }

        return if (query.limit > 0) results.take(query.limit) else results
    }

    /**
     * Gets all symbols in the index.
     */
    fun getAllSymbols(): List<IndexedSymbol> {
        val results = mutableListOf<IndexedSymbol>()

        for (fileIndex in fileIndexes.values) {
            results.addAll(fileIndex.getTopLevelSymbols())
        }

        stdlibIndex?.getAllSymbols()?.let { results.addAll(it) }
        classpathIndex?.getAllSymbols()?.let { results.addAll(it) }

        return results.distinctBy { it.fqName }
    }

    /**
     * Clears all indexed data.
     */
    fun clear() {
        fileIndexes.clear()
        packageIndex.clear()
        extensionIndex.clear()
        stdlibIndex = null
        classpathIndex = null
    }

    /**
     * Creates an IndexData snapshot of all project symbols.
     */
    fun toIndexData(): IndexData {
        val allSymbols = mutableListOf<IndexedSymbol>()

        for (fileIndex in fileIndexes.values) {
            allSymbols.addAll(fileIndex.getTopLevelSymbols())
        }

        return IndexData.fromSymbols(allSymbols)
    }

    private fun addToPackageIndex(fileIndex: FileIndex) {
        for (symbol in fileIndex.getTopLevelSymbols()) {
            packageIndex.add(symbol)
        }
    }

    private fun removeFromPackageIndex(fileIndex: FileIndex) {
        for (symbol in fileIndex.getTopLevelSymbols()) {
            packageIndex.remove(symbol.fqName)
        }
    }

    private fun addToExtensionIndex(fileIndex: FileIndex) {
        extensionIndex.addAll(fileIndex.getExtensions())
    }

    private fun removeFromExtensionIndex(fileIndex: FileIndex) {
        for (extension in fileIndex.getExtensions()) {
            extensionIndex.remove(extension)
        }
    }

    fun findSymbolReferences(fqName: String): List<SymbolReference> {
        val references = mutableListOf<SymbolReference>()

        for ((filePath, fileIndex) in fileIndexes) {
            val fileRefs = findReferencesInFile(fileIndex, fqName)
            for (ref in fileRefs) {
                references.add(SymbolReference(
                    filePath = filePath,
                    symbolFqName = fqName,
                    referenceFqName = ref.fqName,
                    referenceKind = ref.kind
                ))
            }
        }

        return references
    }

    private fun findReferencesInFile(fileIndex: FileIndex, targetFqName: String): List<IndexedSymbol> {
        return fileIndex.getTopLevelSymbols().filter { symbol ->
            when (symbol.kind) {
                IndexedSymbolKind.FUNCTION,
                IndexedSymbolKind.CONSTRUCTOR -> {
                    symbol.parameters.any { it.type == targetFqName || it.type.endsWith(".$targetFqName") } ||
                    symbol.returnType == targetFqName ||
                    symbol.receiverType == targetFqName
                }
                IndexedSymbolKind.PROPERTY -> {
                    symbol.returnType == targetFqName ||
                    symbol.receiverType == targetFqName
                }
                IndexedSymbolKind.CLASS,
                IndexedSymbolKind.INTERFACE,
                IndexedSymbolKind.ENUM_CLASS,
                IndexedSymbolKind.OBJECT -> {
                    symbol.superTypes.any { it == targetFqName || it.endsWith(".$targetFqName") }
                }
                else -> false
            }
        }
    }

    override fun toString(): String {
        return "ProjectIndex(files=$fileCount, symbols=$size, stdlib=${stdlibIndex != null})"
    }
}

data class SymbolReference(
    val filePath: String,
    val symbolFqName: String,
    val referenceFqName: String,
    val referenceKind: IndexedSymbolKind
)
