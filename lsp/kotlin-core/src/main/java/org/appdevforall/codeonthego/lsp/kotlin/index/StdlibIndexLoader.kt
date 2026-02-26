package org.appdevforall.codeonthego.lsp.kotlin.index

import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.Reader

/**
 * Loader for stdlib index from JSON files.
 *
 * StdlibIndexLoader handles deserialization of the pre-generated
 * stdlib-index.json file into a usable StdlibIndex.
 *
 * ## File Formats
 *
 * Two formats are supported:
 * 1. **IndexData** - Simple flat list of symbols
 * 2. **StdlibIndexData** - Optimized format with class hierarchy
 *
 * ## Usage
 *
 * ```kotlin
 * // From Android assets
 * val index = StdlibIndexLoader.loadFromAssets(context.assets)
 *
 * // From InputStream
 * val inputStream = File("stdlib-index.json").inputStream()
 * val index = StdlibIndexLoader.loadFromStream(inputStream)
 *
 * // From JSON string
 * val json = File("stdlib-index.json").readText()
 * val index = StdlibIndexLoader.loadFromJson(json)
 * ```
 */
object StdlibIndexLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Loads StdlibIndex from a JSON string.
     *
     * Auto-detects format (IndexData or StdlibIndexData).
     *
     * @param jsonString The JSON content
     * @return Loaded StdlibIndex
     * @throws kotlinx.serialization.SerializationException on parse error
     */
    fun loadFromJson(jsonString: String): StdlibIndex {
        return if (jsonString.contains("\"classes\":")) {
            if (jsonString.contains("\"members\":")) {
                val data = json.decodeFromString<StdlibIndexData>(jsonString)
                StdlibIndex.fromData(data)
            } else {
                val data = json.decodeFromString<IndexData>(jsonString)
                StdlibIndex.fromIndexData(data)
            }
        } else {
            StdlibIndex.empty()
        }
    }

    /**
     * Loads StdlibIndex from an InputStream.
     *
     * @param inputStream The input stream (will be closed)
     * @return Loaded StdlibIndex
     */
    fun loadFromStream(inputStream: InputStream): StdlibIndex {
        return inputStream.use { stream ->
            val jsonString = stream.bufferedReader().readText()
            loadFromJson(jsonString)
        }
    }

    /**
     * Loads StdlibIndex from a Reader.
     *
     * @param reader The reader (will be closed)
     * @return Loaded StdlibIndex
     */
    fun loadFromReader(reader: Reader): StdlibIndex {
        return reader.use {
            val jsonString = it.readText()
            loadFromJson(jsonString)
        }
    }

    /**
     * Loads IndexData from JSON string.
     *
     * @param jsonString The JSON content
     * @return Parsed IndexData
     */
    fun parseIndexData(jsonString: String): IndexData {
        return json.decodeFromString<IndexData>(jsonString)
    }

    /**
     * Loads StdlibIndexData from JSON string.
     *
     * @param jsonString The JSON content
     * @return Parsed StdlibIndexData
     */
    fun parseStdlibIndexData(jsonString: String): StdlibIndexData {
        return json.decodeFromString<StdlibIndexData>(jsonString)
    }

    /**
     * Serializes IndexData to JSON string.
     *
     * @param data The index data
     * @param pretty Whether to pretty-print
     * @return JSON string
     */
    fun toJson(data: IndexData, pretty: Boolean = false): String {
        val encoder = if (pretty) {
            Json {
                prettyPrint = true
                encodeDefaults = false
            }
        } else {
            Json {
                encodeDefaults = false
            }
        }
        return encoder.encodeToString(IndexData.serializer(), data)
    }

    /**
     * Serializes StdlibIndexData to JSON string.
     *
     * @param data The stdlib index data
     * @param pretty Whether to pretty-print
     * @return JSON string
     */
    fun toJson(data: StdlibIndexData, pretty: Boolean = false): String {
        val encoder = if (pretty) {
            Json {
                prettyPrint = true
                encodeDefaults = false
            }
        } else {
            Json {
                encodeDefaults = false
            }
        }
        return encoder.encodeToString(StdlibIndexData.serializer(), data)
    }

    /**
     * Creates a minimal stdlib index with essential types.
     *
     * Useful for testing or when full index is unavailable.
     *
     * @return StdlibIndex with core types only
     */
    fun createMinimalIndex(): StdlibIndex {
        val index = StdlibIndex(version = "minimal", kotlinVersion = "2.0")

        addPrimitiveTypes(index)
        addCoreClasses(index)
        addCoreFunctions(index)

        return index
    }

    private fun addPrimitiveTypes(index: StdlibIndex) {
        val primitives = listOf(
            "Int" to "32-bit signed integer",
            "Long" to "64-bit signed integer",
            "Short" to "16-bit signed integer",
            "Byte" to "8-bit signed integer",
            "Float" to "32-bit floating point",
            "Double" to "64-bit floating point",
            "Boolean" to "Boolean value (true/false)",
            "Char" to "16-bit Unicode character"
        )

        for ((name, _) in primitives) {
            index.addSymbol(IndexedSymbol(
                name = name,
                fqName = "kotlin.$name",
                kind = IndexedSymbolKind.CLASS,
                packageName = "kotlin",
                superTypes = listOf("kotlin.Number", "kotlin.Comparable<kotlin.$name>")
            ))
        }
    }

    private fun addCoreClasses(index: StdlibIndex) {
        index.addSymbol(IndexedSymbol(
            name = "Any",
            fqName = "kotlin.Any",
            kind = IndexedSymbolKind.CLASS,
            packageName = "kotlin"
        ))

        index.addSymbol(IndexedSymbol(
            name = "Nothing",
            fqName = "kotlin.Nothing",
            kind = IndexedSymbolKind.CLASS,
            packageName = "kotlin"
        ))

        index.addSymbol(IndexedSymbol(
            name = "Unit",
            fqName = "kotlin.Unit",
            kind = IndexedSymbolKind.OBJECT,
            packageName = "kotlin"
        ))

        index.addSymbol(IndexedSymbol(
            name = "String",
            fqName = "kotlin.String",
            kind = IndexedSymbolKind.CLASS,
            packageName = "kotlin",
            superTypes = listOf("kotlin.Comparable<kotlin.String>", "kotlin.CharSequence")
        ))

        index.addSymbol(IndexedSymbol(
            name = "CharSequence",
            fqName = "kotlin.CharSequence",
            kind = IndexedSymbolKind.INTERFACE,
            packageName = "kotlin"
        ))

        index.addSymbol(IndexedSymbol(
            name = "Comparable",
            fqName = "kotlin.Comparable",
            kind = IndexedSymbolKind.INTERFACE,
            packageName = "kotlin",
            typeParameters = listOf("T")
        ))

        index.addSymbol(IndexedSymbol(
            name = "Number",
            fqName = "kotlin.Number",
            kind = IndexedSymbolKind.CLASS,
            packageName = "kotlin"
        ))

        index.addSymbol(IndexedSymbol(
            name = "Throwable",
            fqName = "kotlin.Throwable",
            kind = IndexedSymbolKind.CLASS,
            packageName = "kotlin"
        ))

        index.addSymbol(IndexedSymbol(
            name = "Exception",
            fqName = "kotlin.Exception",
            kind = IndexedSymbolKind.CLASS,
            packageName = "kotlin",
            superTypes = listOf("kotlin.Throwable")
        ))

        addCollectionTypes(index)
    }

    private fun addCollectionTypes(index: StdlibIndex) {
        val collections = listOf(
            Triple("Iterable", "INTERFACE", listOf("T")),
            Triple("Collection", "INTERFACE", listOf("E")),
            Triple("List", "INTERFACE", listOf("E")),
            Triple("Set", "INTERFACE", listOf("E")),
            Triple("Map", "INTERFACE", listOf("K", "V")),
            Triple("MutableIterable", "INTERFACE", listOf("T")),
            Triple("MutableCollection", "INTERFACE", listOf("E")),
            Triple("MutableList", "INTERFACE", listOf("E")),
            Triple("MutableSet", "INTERFACE", listOf("E")),
            Triple("MutableMap", "INTERFACE", listOf("K", "V")),
            Triple("Sequence", "INTERFACE", listOf("T"))
        )

        for ((name, kind, typeParams) in collections) {
            index.addSymbol(IndexedSymbol(
                name = name,
                fqName = "kotlin.collections.$name",
                kind = IndexedSymbolKind.valueOf(kind),
                packageName = "kotlin.collections",
                typeParameters = typeParams
            ))
        }

        index.addSymbol(IndexedSymbol(
            name = "Pair",
            fqName = "kotlin.Pair",
            kind = IndexedSymbolKind.DATA_CLASS,
            packageName = "kotlin",
            typeParameters = listOf("A", "B")
        ))

        index.addSymbol(IndexedSymbol(
            name = "Triple",
            fqName = "kotlin.Triple",
            kind = IndexedSymbolKind.DATA_CLASS,
            packageName = "kotlin",
            typeParameters = listOf("A", "B", "C")
        ))
    }

    private fun addCoreFunctions(index: StdlibIndex) {
        index.addSymbol(IndexedSymbol(
            name = "println",
            fqName = "kotlin.io.println",
            kind = IndexedSymbolKind.FUNCTION,
            packageName = "kotlin.io",
            parameters = listOf(IndexedParameter("message", "Any?", hasDefault = true)),
            returnType = "Unit"
        ))

        index.addSymbol(IndexedSymbol(
            name = "print",
            fqName = "kotlin.io.print",
            kind = IndexedSymbolKind.FUNCTION,
            packageName = "kotlin.io",
            parameters = listOf(IndexedParameter("message", "Any?")),
            returnType = "Unit"
        ))

        val collectionCreators = listOf(
            "listOf" to "List<T>",
            "mutableListOf" to "MutableList<T>",
            "setOf" to "Set<T>",
            "mutableSetOf" to "MutableSet<T>",
            "arrayOf" to "Array<T>",
            "emptyList" to "List<T>",
            "emptySet" to "Set<T>",
            "emptyMap" to "Map<K, V>"
        )

        for ((name, returnType) in collectionCreators) {
            index.addSymbol(IndexedSymbol(
                name = name,
                fqName = "kotlin.collections.$name",
                kind = IndexedSymbolKind.FUNCTION,
                packageName = "kotlin.collections",
                parameters = listOf(IndexedParameter("elements", "T", isVararg = true)),
                returnType = returnType,
                typeParameters = if (returnType.contains("Map")) listOf("K", "V") else listOf("T")
            ))
        }

        index.addSymbol(IndexedSymbol(
            name = "mapOf",
            fqName = "kotlin.collections.mapOf",
            kind = IndexedSymbolKind.FUNCTION,
            packageName = "kotlin.collections",
            parameters = listOf(IndexedParameter("pairs", "Pair<K, V>", isVararg = true)),
            returnType = "Map<K, V>",
            typeParameters = listOf("K", "V")
        ))

        index.addSymbol(IndexedSymbol(
            name = "mutableMapOf",
            fqName = "kotlin.collections.mutableMapOf",
            kind = IndexedSymbolKind.FUNCTION,
            packageName = "kotlin.collections",
            parameters = listOf(IndexedParameter("pairs", "Pair<K, V>", isVararg = true)),
            returnType = "MutableMap<K, V>",
            typeParameters = listOf("K", "V")
        ))

        addScopeFunctions(index)
    }

    private fun addScopeFunctions(index: StdlibIndex) {
        index.addSymbol(IndexedSymbol(
            name = "let",
            fqName = "kotlin.let",
            kind = IndexedSymbolKind.FUNCTION,
            packageName = "kotlin",
            typeParameters = listOf("T", "R"),
            parameters = listOf(IndexedParameter("block", "(T) -> R")),
            returnType = "R",
            receiverType = "T"
        ))

        index.addSymbol(IndexedSymbol(
            name = "run",
            fqName = "kotlin.run",
            kind = IndexedSymbolKind.FUNCTION,
            packageName = "kotlin",
            typeParameters = listOf("T", "R"),
            parameters = listOf(IndexedParameter("block", "T.() -> R")),
            returnType = "R",
            receiverType = "T"
        ))

        index.addSymbol(IndexedSymbol(
            name = "with",
            fqName = "kotlin.with",
            kind = IndexedSymbolKind.FUNCTION,
            packageName = "kotlin",
            typeParameters = listOf("T", "R"),
            parameters = listOf(
                IndexedParameter("receiver", "T"),
                IndexedParameter("block", "T.() -> R")
            ),
            returnType = "R"
        ))

        index.addSymbol(IndexedSymbol(
            name = "apply",
            fqName = "kotlin.apply",
            kind = IndexedSymbolKind.FUNCTION,
            packageName = "kotlin",
            typeParameters = listOf("T"),
            parameters = listOf(IndexedParameter("block", "T.() -> Unit")),
            returnType = "T",
            receiverType = "T"
        ))

        index.addSymbol(IndexedSymbol(
            name = "also",
            fqName = "kotlin.also",
            kind = IndexedSymbolKind.FUNCTION,
            packageName = "kotlin",
            typeParameters = listOf("T"),
            parameters = listOf(IndexedParameter("block", "(T) -> Unit")),
            returnType = "T",
            receiverType = "T"
        ))

        index.addSymbol(IndexedSymbol(
            name = "takeIf",
            fqName = "kotlin.takeIf",
            kind = IndexedSymbolKind.FUNCTION,
            packageName = "kotlin",
            typeParameters = listOf("T"),
            parameters = listOf(IndexedParameter("predicate", "(T) -> Boolean")),
            returnType = "T?",
            receiverType = "T"
        ))

        index.addSymbol(IndexedSymbol(
            name = "takeUnless",
            fqName = "kotlin.takeUnless",
            kind = IndexedSymbolKind.FUNCTION,
            packageName = "kotlin",
            typeParameters = listOf("T"),
            parameters = listOf(IndexedParameter("predicate", "(T) -> Boolean")),
            returnType = "T?",
            receiverType = "T"
        ))

        index.addSymbol(IndexedSymbol(
            name = "to",
            fqName = "kotlin.to",
            kind = IndexedSymbolKind.FUNCTION,
            packageName = "kotlin",
            typeParameters = listOf("A", "B"),
            parameters = listOf(IndexedParameter("that", "B")),
            returnType = "Pair<A, B>",
            receiverType = "A"
        ))
    }
}
