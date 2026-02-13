package org.appdevforall.codeonthego.lsp.kotlin.index

import kotlinx.serialization.Serializable
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Visibility

/**
 * Serializable index entry for persistent storage.
 *
 * IndexEntry is the JSON-serializable form of symbol index data.
 * It's used for:
 * - Saving/loading project indexes
 * - Distributing stdlib index in app assets
 * - Caching index data
 *
 * ## JSON Format
 *
 * ```json
 * {
 *   "name": "String",
 *   "fqName": "kotlin.String",
 *   "kind": "CLASS",
 *   "pkg": "kotlin",
 *   "sig": "class String : Comparable<String>, CharSequence",
 *   "vis": "PUBLIC",
 *   "params": [],
 *   "typeParams": [],
 *   "ret": null,
 *   "recv": null,
 *   "supers": ["kotlin.Comparable", "kotlin.CharSequence"],
 *   "dep": false
 * }
 * ```
 */
@Serializable
data class IndexEntry(
    val name: String,
    val fqName: String,
    val kind: String,
    val pkg: String,
    val container: String? = null,
    val sig: String = "",
    val vis: String = "PUBLIC",
    val typeParams: List<String> = emptyList(),
    val params: List<ParamEntry> = emptyList(),
    val ret: String? = null,
    val recv: String? = null,
    val supers: List<String> = emptyList(),
    val file: String? = null,
    val dep: Boolean = false,
    val depMsg: String? = null
) {
    /**
     * Converts to an IndexedSymbol.
     */
    fun toIndexedSymbol(): IndexedSymbol {
        return IndexedSymbol(
            name = name,
            fqName = fqName,
            kind = parseKind(kind),
            packageName = pkg,
            containingClass = container,
            visibility = parseVisibility(vis),
            signature = sig,
            typeParameters = typeParams,
            parameters = params.map { it.toIndexedParameter() },
            returnType = ret,
            receiverType = recv,
            superTypes = supers,
            filePath = file,
            deprecated = dep,
            deprecationMessage = depMsg
        )
    }

    private fun parseKind(kind: String): IndexedSymbolKind {
        return try {
            IndexedSymbolKind.valueOf(kind)
        } catch (e: IllegalArgumentException) {
            IndexedSymbolKind.CLASS
        }
    }

    private fun parseVisibility(vis: String): Visibility {
        return try {
            Visibility.valueOf(vis)
        } catch (e: IllegalArgumentException) {
            Visibility.PUBLIC
        }
    }

    companion object {
        /**
         * Creates an IndexEntry from an IndexedSymbol.
         */
        fun fromIndexedSymbol(symbol: IndexedSymbol): IndexEntry {
            return IndexEntry(
                name = symbol.name,
                fqName = symbol.fqName,
                kind = symbol.kind.name,
                pkg = symbol.packageName,
                container = symbol.containingClass,
                sig = symbol.signature,
                vis = symbol.visibility.name,
                typeParams = symbol.typeParameters,
                params = symbol.parameters.map { ParamEntry.fromIndexedParameter(it) },
                ret = symbol.returnType,
                recv = symbol.receiverType,
                supers = symbol.superTypes,
                file = symbol.filePath,
                dep = symbol.deprecated,
                depMsg = symbol.deprecationMessage
            )
        }
    }
}

/**
 * Serializable parameter entry.
 */
@Serializable
data class ParamEntry(
    val name: String,
    val type: String,
    val def: Boolean = false,
    val vararg: Boolean = false
) {
    fun toIndexedParameter(): IndexedParameter {
        return IndexedParameter(
            name = name,
            type = type,
            hasDefault = def,
            isVararg = vararg
        )
    }

    companion object {
        fun fromIndexedParameter(param: IndexedParameter): ParamEntry {
            return ParamEntry(
                name = param.name,
                type = param.type,
                def = param.hasDefault,
                vararg = param.isVararg
            )
        }
    }
}

/**
 * Complete index data for serialization.
 *
 * Contains all symbols organized by category for efficient loading.
 */
@Serializable
data class IndexData(
    val version: String = "1.0",
    val kotlinVersion: String = "",
    val generatedAt: Long = System.currentTimeMillis(),
    val classes: List<IndexEntry> = emptyList(),
    val functions: List<IndexEntry> = emptyList(),
    val properties: List<IndexEntry> = emptyList(),
    val typeAliases: List<IndexEntry> = emptyList(),
    val extensions: List<IndexEntry> = emptyList()
) {
    /**
     * Total number of entries.
     */
    val totalCount: Int get() = classes.size + functions.size + properties.size +
            typeAliases.size + extensions.size

    /**
     * Gets all entries as a single list.
     */
    fun allEntries(): List<IndexEntry> {
        return classes + functions + properties + typeAliases + extensions
    }

    /**
     * Converts all entries to IndexedSymbols.
     */
    fun toIndexedSymbols(): List<IndexedSymbol> {
        return allEntries().map { it.toIndexedSymbol() }
    }

    companion object {
        val EMPTY = IndexData()

        /**
         * Creates IndexData from a list of IndexedSymbols.
         */
        fun fromSymbols(
            symbols: List<IndexedSymbol>,
            version: String = "1.0",
            kotlinVersion: String = ""
        ): IndexData {
            val classes = mutableListOf<IndexEntry>()
            val functions = mutableListOf<IndexEntry>()
            val properties = mutableListOf<IndexEntry>()
            val typeAliases = mutableListOf<IndexEntry>()
            val extensions = mutableListOf<IndexEntry>()

            for (symbol in symbols) {
                val entry = IndexEntry.fromIndexedSymbol(symbol)
                when {
                    symbol.isExtension -> extensions.add(entry)
                    symbol.kind == IndexedSymbolKind.TYPE_ALIAS -> typeAliases.add(entry)
                    symbol.kind == IndexedSymbolKind.FUNCTION ||
                    symbol.kind == IndexedSymbolKind.CONSTRUCTOR -> functions.add(entry)
                    symbol.kind == IndexedSymbolKind.PROPERTY -> properties.add(entry)
                    symbol.kind.isClass -> classes.add(entry)
                    else -> classes.add(entry)
                }
            }

            return IndexData(
                version = version,
                kotlinVersion = kotlinVersion,
                classes = classes,
                functions = functions,
                properties = properties,
                typeAliases = typeAliases,
                extensions = extensions
            )
        }
    }
}

/**
 * Class member index entry.
 */
@Serializable
data class MemberEntry(
    val name: String,
    val kind: String,
    val sig: String = "",
    val params: List<ParamEntry> = emptyList(),
    val ret: String? = null,
    val vis: String = "PUBLIC",
    val dep: Boolean = false
)

/**
 * Full class information for stdlib index.
 */
@Serializable
data class ClassEntry(
    val fqName: String,
    val kind: String,
    val typeParams: List<String> = emptyList(),
    val supers: List<String> = emptyList(),
    val members: List<MemberEntry> = emptyList(),
    val companions: List<MemberEntry> = emptyList(),
    val nested: List<String> = emptyList(),
    val dep: Boolean = false,
    val depMsg: String? = null
) {
    val simpleName: String get() = fqName.substringAfterLast('.')
    val packageName: String get() = fqName.substringBeforeLast('.', "")

    /**
     * Converts to IndexedSymbols (class + members).
     */
    fun toIndexedSymbols(): List<IndexedSymbol> {
        val result = mutableListOf<IndexedSymbol>()

        result.add(IndexedSymbol(
            name = simpleName,
            fqName = fqName,
            kind = parseKind(kind),
            packageName = packageName,
            typeParameters = typeParams,
            superTypes = supers,
            deprecated = dep,
            deprecationMessage = depMsg
        ))

        for (member in members) {
            result.add(IndexedSymbol(
                name = member.name,
                fqName = "$fqName.${member.name}",
                kind = parseKind(member.kind),
                packageName = packageName,
                containingClass = fqName,
                signature = member.sig,
                parameters = member.params.map { it.toIndexedParameter() },
                returnType = member.ret,
                visibility = parseVisibility(member.vis),
                deprecated = member.dep
            ))
        }

        return result
    }

    private fun parseKind(kind: String): IndexedSymbolKind {
        return try {
            IndexedSymbolKind.valueOf(kind)
        } catch (e: IllegalArgumentException) {
            IndexedSymbolKind.CLASS
        }
    }

    private fun parseVisibility(vis: String): Visibility {
        return try {
            Visibility.valueOf(vis)
        } catch (e: IllegalArgumentException) {
            Visibility.PUBLIC
        }
    }
}

/**
 * Stdlib index format.
 */
@Serializable
data class StdlibIndexData(
    val version: String,
    val kotlinVersion: String,
    val generatedAt: Long = System.currentTimeMillis(),
    val classes: Map<String, ClassEntry> = emptyMap(),
    val topLevelFunctions: List<IndexEntry> = emptyList(),
    val topLevelProperties: List<IndexEntry> = emptyList(),
    val extensions: Map<String, List<IndexEntry>> = emptyMap(),
    val typeAliases: List<IndexEntry> = emptyList()
) {
    /**
     * Total symbol count.
     */
    val totalCount: Int get() {
        var count = classes.size + topLevelFunctions.size + topLevelProperties.size + typeAliases.size
        classes.values.forEach { count += it.members.size }
        extensions.values.forEach { count += it.size }
        return count
    }

    /**
     * Gets all top-level symbols as IndexedSymbols.
     */
    fun toIndexedSymbols(): List<IndexedSymbol> {
        val result = mutableListOf<IndexedSymbol>()

        classes.values.forEach { classEntry ->
            result.addAll(classEntry.toIndexedSymbols())
        }

        topLevelFunctions.forEach { result.add(it.toIndexedSymbol()) }
        topLevelProperties.forEach { result.add(it.toIndexedSymbol()) }
        typeAliases.forEach { result.add(it.toIndexedSymbol()) }

        extensions.values.flatten().forEach { result.add(it.toIndexedSymbol()) }

        return result
    }

    companion object {
        val EMPTY = StdlibIndexData(version = "1.0", kotlinVersion = "")
    }
}
