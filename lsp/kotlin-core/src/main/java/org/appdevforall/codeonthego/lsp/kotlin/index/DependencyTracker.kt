package org.appdevforall.codeonthego.lsp.kotlin.index

import java.util.concurrent.ConcurrentHashMap

class DependencyTracker {
    private val dependsOn = ConcurrentHashMap<String, MutableSet<String>>()
    private val dependedBy = ConcurrentHashMap<String, MutableSet<String>>()

    fun addDependency(fromUri: String, toSymbolFqName: String) {
        dependsOn.getOrPut(fromUri) { ConcurrentHashMap.newKeySet() }.add(toSymbolFqName)
        dependedBy.getOrPut(toSymbolFqName) { ConcurrentHashMap.newKeySet() }.add(fromUri)
    }

    fun addImportDependency(fromUri: String, importedFqName: String) {
        addDependency(fromUri, importedFqName)
    }

    fun addTypeDependency(fromUri: String, typeFqName: String) {
        addDependency(fromUri, typeFqName)
    }

    fun clearDependencies(uri: String) {
        val deps = dependsOn.remove(uri)
        deps?.forEach { symbolFqName ->
            dependedBy[symbolFqName]?.remove(uri)
        }
    }

    fun getDependencies(uri: String): Set<String> {
        return dependsOn[uri]?.toSet() ?: emptySet()
    }

    fun getDependentFiles(symbolFqName: String): Set<String> {
        return dependedBy[symbolFqName]?.toSet() ?: emptySet()
    }

    fun getFilesToInvalidate(changedUri: String, definedSymbols: Set<String>): Set<String> {
        val filesToInvalidate = mutableSetOf<String>()

        for (symbol in definedSymbols) {
            val dependents = getDependentFiles(symbol)
            filesToInvalidate.addAll(dependents)
        }

        filesToInvalidate.remove(changedUri)
        return filesToInvalidate
    }

    fun hasAnyDependents(symbolFqName: String): Boolean {
        return dependedBy[symbolFqName]?.isNotEmpty() == true
    }

    fun trackDependenciesFromImports(uri: String, imports: List<ImportInfo>) {
        clearDependencies(uri)
        for (import in imports) {
            addImportDependency(uri, import.fqName)
        }
    }

    fun trackDependenciesFromSymbolUsage(uri: String, usedSymbolFqNames: Set<String>) {
        for (fqName in usedSymbolFqNames) {
            addDependency(uri, fqName)
        }
    }

    fun clear() {
        dependsOn.clear()
        dependedBy.clear()
    }

    fun stats(): DependencyStats {
        return DependencyStats(
            totalFiles = dependsOn.size,
            totalSymbols = dependedBy.size,
            totalDependencies = dependsOn.values.sumOf { it.size }
        )
    }
}

data class ImportInfo(
    val fqName: String,
    val alias: String? = null,
    val isStar: Boolean = false
)

data class DependencyStats(
    val totalFiles: Int,
    val totalSymbols: Int,
    val totalDependencies: Int
)
