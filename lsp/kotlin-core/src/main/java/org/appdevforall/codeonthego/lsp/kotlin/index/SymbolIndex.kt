package org.appdevforall.codeonthego.lsp.kotlin.index

import org.appdevforall.codeonthego.lsp.kotlin.symbol.ClassKind
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ClassSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.FunctionSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Modifiers
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ParameterSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.PropertySymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Symbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolLocation
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeParameterSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeReference
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Visibility

/**
 * Interface for symbol lookup operations.
 *
 * SymbolIndex provides efficient lookup of symbols by various criteria:
 * - By fully qualified name (exact match)
 * - By simple name (may return multiple results)
 * - By package (all symbols in a package)
 * - By prefix (for code completion)
 *
 * Implementations include:
 * - [FileIndex]: Per-file symbol storage
 * - [ProjectIndex]: Project-wide aggregation
 * - [StdlibIndex]: Kotlin standard library symbols
 *
 * ## Thread Safety
 *
 * Index implementations should be thread-safe for read operations.
 * Write operations (add/remove) may require external synchronization.
 *
 * ## Usage
 *
 * ```kotlin
 * val index: SymbolIndex = projectIndex
 * val stringClass = index.findByFqName("kotlin.String")
 * val listFunctions = index.findBySimpleName("listOf")
 * val completions = index.findByPrefix("str")
 * ```
 */
interface SymbolIndex {

    /**
     * Finds a symbol by its fully qualified name.
     *
     * @param fqName The fully qualified name (e.g., "kotlin.String")
     * @return The indexed symbol, or null if not found
     */
    fun findByFqName(fqName: String): IndexedSymbol?

    /**
     * Finds all symbols with a given simple name.
     *
     * @param name The simple name (e.g., "String")
     * @return List of matching symbols from all packages
     */
    fun findBySimpleName(name: String): List<IndexedSymbol>

    /**
     * Finds all symbols in a package.
     *
     * @param packageName The package name (e.g., "kotlin.collections")
     * @return List of top-level symbols in that package
     */
    fun findByPackage(packageName: String): List<IndexedSymbol>

    /**
     * Finds symbols whose simple name starts with a prefix.
     *
     * Used for code completion.
     *
     * @param prefix The name prefix (e.g., "str" matches "String", "stringify")
     * @param limit Maximum number of results (0 for unlimited)
     * @return List of matching symbols
     */
    fun findByPrefix(prefix: String, limit: Int = 0): List<IndexedSymbol>

    /**
     * Gets all class symbols in the index.
     */
    fun getAllClasses(): List<IndexedSymbol>

    /**
     * Gets all function symbols in the index.
     */
    fun getAllFunctions(): List<IndexedSymbol>

    /**
     * Gets all property symbols in the index.
     */
    fun getAllProperties(): List<IndexedSymbol>

    /**
     * Gets the total number of indexed symbols.
     */
    val size: Int

    /**
     * Whether this index is empty.
     */
    val isEmpty: Boolean get() = size == 0

    /**
     * Whether this index contains a symbol with the given FQ name.
     */
    fun contains(fqName: String): Boolean = findByFqName(fqName) != null
}

/**
 * Mutable symbol index that supports adding and removing symbols.
 */
interface MutableSymbolIndex : SymbolIndex {

    /**
     * Adds a symbol to the index.
     *
     * @param symbol The symbol to add
     */
    fun add(symbol: IndexedSymbol)

    /**
     * Adds multiple symbols to the index.
     *
     * @param symbols The symbols to add
     */
    fun addAll(symbols: Iterable<IndexedSymbol>) {
        symbols.forEach { add(it) }
    }

    /**
     * Removes a symbol from the index.
     *
     * @param fqName The fully qualified name of the symbol to remove
     * @return true if the symbol was found and removed
     */
    fun remove(fqName: String): Boolean

    /**
     * Removes all symbols from the index.
     */
    fun clear()
}

/**
 * Kind of indexed symbol.
 */
enum class IndexedSymbolKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM_CLASS,
    ANNOTATION_CLASS,
    DATA_CLASS,
    VALUE_CLASS,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
    CONSTRUCTOR;

    val isClass: Boolean get() = this in setOf(
        CLASS, INTERFACE, OBJECT, ENUM_CLASS, ANNOTATION_CLASS, DATA_CLASS, VALUE_CLASS
    )

    val isCallable: Boolean get() = this in setOf(FUNCTION, CONSTRUCTOR)

    companion object {
        fun fromClassKind(kind: ClassKind): IndexedSymbolKind = when (kind) {
            ClassKind.CLASS -> CLASS
            ClassKind.INTERFACE -> INTERFACE
            ClassKind.OBJECT -> OBJECT
            ClassKind.COMPANION_OBJECT -> OBJECT
            ClassKind.ENUM_CLASS -> ENUM_CLASS
            ClassKind.ENUM_ENTRY -> OBJECT
            ClassKind.ANNOTATION_CLASS -> ANNOTATION_CLASS
            ClassKind.DATA_CLASS -> DATA_CLASS
            ClassKind.VALUE_CLASS -> VALUE_CLASS
        }
    }
}

/**
 * A symbol stored in the index with metadata for efficient lookup.
 *
 * IndexedSymbol contains just enough information for:
 * - Symbol identification (name, FQ name, kind)
 * - Visibility filtering
 * - Type signature (for overload resolution)
 * - Documentation
 *
 * Full symbol information can be retrieved by loading the source file.
 *
 * @property name Simple name of the symbol
 * @property fqName Fully qualified name
 * @property kind The kind of symbol
 * @property packageName The containing package
 * @property containingClass FQ name of containing class (for members)
 * @property visibility Symbol visibility
 * @property signature Type signature for display
 * @property typeParameters Generic type parameter names
 * @property parameters Parameter information for functions
 * @property returnType Return type for functions/properties
 * @property receiverType Extension receiver type (if extension)
 * @property superTypes Direct supertypes (for classes)
 * @property filePath Source file path (if from project)
 * @property deprecated Whether this symbol is deprecated
 * @property deprecationMessage Deprecation message if deprecated
 */
data class IndexedSymbol(
    val name: String,
    val fqName: String,
    val kind: IndexedSymbolKind,
    val packageName: String,
    val containingClass: String? = null,
    val visibility: Visibility = Visibility.PUBLIC,
    val signature: String = "",
    val typeParameters: List<String> = emptyList(),
    val parameters: List<IndexedParameter> = emptyList(),
    val returnType: String? = null,
    val receiverType: String? = null,
    val superTypes: List<String> = emptyList(),
    val filePath: String? = null,
    val startLine: Int? = null,
    val startColumn: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null,
    val deprecated: Boolean = false,
    val deprecationMessage: String? = null
) {
    /**
     * Simple name for display.
     */
    val simpleName: String get() = name

    /**
     * Whether this is a top-level declaration.
     */
    val isTopLevel: Boolean get() = containingClass == null

    /**
     * Whether this is a member of a class.
     */
    val isMember: Boolean get() = containingClass != null

    /**
     * Whether this is an extension function/property.
     */
    val isExtension: Boolean get() = receiverType != null

    /**
     * Whether this is a generic symbol.
     */
    val isGeneric: Boolean get() = typeParameters.isNotEmpty()

    /**
     * Whether this symbol is from the standard library.
     */
    val isStdlib: Boolean get() = packageName.startsWith("kotlin")

    /**
     * Whether this symbol has source location information.
     */
    val hasLocation: Boolean get() = startLine != null && startColumn != null

    /**
     * Whether this symbol is visible from outside its module.
     */
    val isPublicApi: Boolean get() = visibility == Visibility.PUBLIC || visibility == Visibility.PROTECTED

    /**
     * The arity (parameter count) for functions.
     */
    val arity: Int get() = parameters.size

    /**
     * Creates a display string for the symbol.
     */
    fun toDisplayString(): String = buildString {
        when (kind) {
            IndexedSymbolKind.CLASS -> append("class ")
            IndexedSymbolKind.INTERFACE -> append("interface ")
            IndexedSymbolKind.OBJECT -> append("object ")
            IndexedSymbolKind.ENUM_CLASS -> append("enum class ")
            IndexedSymbolKind.ANNOTATION_CLASS -> append("annotation class ")
            IndexedSymbolKind.DATA_CLASS -> append("data class ")
            IndexedSymbolKind.VALUE_CLASS -> append("value class ")
            IndexedSymbolKind.FUNCTION -> append("fun ")
            IndexedSymbolKind.PROPERTY -> append(if (returnType != null) "val " else "var ")
            IndexedSymbolKind.TYPE_ALIAS -> append("typealias ")
            IndexedSymbolKind.CONSTRUCTOR -> append("constructor")
        }

        receiverType?.let { append(it).append('.') }
        append(name)

        if (typeParameters.isNotEmpty()) {
            append('<')
            append(typeParameters.joinToString())
            append('>')
        }

        if (kind.isCallable || kind == IndexedSymbolKind.CONSTRUCTOR) {
            append('(')
            append(parameters.joinToString { "${it.name}: ${it.type}" })
            append(')')
        }

        returnType?.let { append(": ").append(it) }
    }

    override fun toString(): String = fqName

    fun toSyntheticSymbol(): Symbol {
        return when {
            kind.isClass -> ClassSymbol(
                name = fqName,
                location = SymbolLocation.SYNTHETIC,
                modifiers = Modifiers.EMPTY,
                containingScope = null,
                kind = when (kind) {
                    IndexedSymbolKind.CLASS -> ClassKind.CLASS
                    IndexedSymbolKind.INTERFACE -> ClassKind.INTERFACE
                    IndexedSymbolKind.OBJECT -> ClassKind.OBJECT
                    IndexedSymbolKind.ENUM_CLASS -> ClassKind.ENUM_CLASS
                    IndexedSymbolKind.ANNOTATION_CLASS -> ClassKind.ANNOTATION_CLASS
                    IndexedSymbolKind.DATA_CLASS -> ClassKind.DATA_CLASS
                    IndexedSymbolKind.VALUE_CLASS -> ClassKind.VALUE_CLASS
                    else -> ClassKind.CLASS
                }
            )
            kind == IndexedSymbolKind.FUNCTION || kind == IndexedSymbolKind.CONSTRUCTOR -> FunctionSymbol(
                name = name,
                location = SymbolLocation.SYNTHETIC,
                modifiers = Modifiers.EMPTY,
                containingScope = null,
                typeParameters = typeParameters.map { tpName ->
                    TypeParameterSymbol(
                        name = tpName,
                        location = SymbolLocation.SYNTHETIC,
                        modifiers = Modifiers.EMPTY,
                        containingScope = null
                    )
                },
                parameters = parameters.map {
                    ParameterSymbol(
                        name = it.name,
                        location = SymbolLocation.SYNTHETIC,
                        modifiers = Modifiers.EMPTY,
                        containingScope = null,
                        type = TypeReference(it.type),
                        hasDefaultValue = it.hasDefault,
                        isVararg = it.isVararg
                    )
                },
                returnType = returnType?.let { TypeReference(it) },
                receiverType = receiverType?.let { TypeReference(it) }
            )
            kind == IndexedSymbolKind.PROPERTY -> PropertySymbol(
                name = name,
                location = SymbolLocation.SYNTHETIC,
                modifiers = Modifiers.EMPTY,
                containingScope = null,
                type = returnType?.let { TypeReference(it) },
                isVar = false,
                receiverType = receiverType?.let { TypeReference(it) }
            )
            else -> ClassSymbol(
                name = fqName,
                location = SymbolLocation.SYNTHETIC,
                modifiers = Modifiers.EMPTY,
                containingScope = null,
                kind = ClassKind.CLASS
            )
        }
    }
}

/**
 * Parameter information for indexed functions.
 */
data class IndexedParameter(
    val name: String,
    val type: String,
    val hasDefault: Boolean = false,
    val isVararg: Boolean = false
) {
    override fun toString(): String = buildString {
        if (isVararg) append("vararg ")
        append(name)
        append(": ")
        append(type)
        if (hasDefault) append(" = ...")
    }
}

/**
 * Index query options.
 */
data class IndexQuery(
    val namePrefix: String? = null,
    val packageName: String? = null,
    val kinds: Set<IndexedSymbolKind>? = null,
    val includeDeprecated: Boolean = true,
    val includeInternal: Boolean = false,
    val extensionReceiverType: String? = null,
    val limit: Int = 0
) {
    companion object {
        val ALL = IndexQuery()

        fun byName(name: String) = IndexQuery(namePrefix = name)

        fun byPackage(packageName: String) = IndexQuery(packageName = packageName)

        fun classes() = IndexQuery(kinds = setOf(
            IndexedSymbolKind.CLASS,
            IndexedSymbolKind.INTERFACE,
            IndexedSymbolKind.OBJECT,
            IndexedSymbolKind.ENUM_CLASS,
            IndexedSymbolKind.DATA_CLASS
        ))

        fun functions() = IndexQuery(kinds = setOf(IndexedSymbolKind.FUNCTION))

        fun extensionsFor(receiverType: String) = IndexQuery(extensionReceiverType = receiverType)
    }
}
