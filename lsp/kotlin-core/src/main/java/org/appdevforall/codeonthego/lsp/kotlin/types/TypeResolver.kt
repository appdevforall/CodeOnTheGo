package org.appdevforall.codeonthego.lsp.kotlin.types

import org.appdevforall.codeonthego.lsp.kotlin.index.IndexedSymbol
import org.appdevforall.codeonthego.lsp.kotlin.index.ProjectIndex
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ImportInfo
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolTable
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeParameterSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeReference
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Scope

/**
 * Resolves type references to concrete KotlinType instances.
 *
 * TypeResolver handles:
 * - Looking up class types by name
 * - Resolving type arguments for generic types
 * - Handling nullable types
 * - Recognizing primitive and built-in types
 *
 * ## Usage
 *
 * ```kotlin
 * val resolver = TypeResolver()
 * val typeRef = TypeReference("List", listOf(TypeReference("String")))
 * val type = resolver.resolve(typeRef, scope)
 * // type is ClassType("kotlin.collections.List", [ClassType("kotlin.String")])
 * ```
 */
class TypeResolver(
    private val hierarchy: TypeHierarchy = TypeHierarchy.DEFAULT
) {
    private val primitiveNames = mapOf(
        "Int" to PrimitiveType.INT,
        "Long" to PrimitiveType.LONG,
        "Float" to PrimitiveType.FLOAT,
        "Double" to PrimitiveType.DOUBLE,
        "Boolean" to PrimitiveType.BOOLEAN,
        "Char" to PrimitiveType.CHAR,
        "Byte" to PrimitiveType.BYTE,
        "Short" to PrimitiveType.SHORT
    )

    private val builtinTypes = mapOf(
        "Any" to ClassType.ANY,
        "Unit" to ClassType.UNIT,
        "Nothing" to ClassType.NOTHING,
        "String" to ClassType.STRING,
        "CharSequence" to ClassType.CHAR_SEQUENCE,
        "Number" to ClassType("kotlin.Number"),
        "Comparable" to ClassType.COMPARABLE,
        "Throwable" to ClassType.THROWABLE,
        "Exception" to ClassType.EXCEPTION,

        "Array" to ClassType.ARRAY,
        "IntArray" to ClassType("kotlin.IntArray"),
        "LongArray" to ClassType("kotlin.LongArray"),
        "FloatArray" to ClassType("kotlin.FloatArray"),
        "DoubleArray" to ClassType("kotlin.DoubleArray"),
        "BooleanArray" to ClassType("kotlin.BooleanArray"),
        "CharArray" to ClassType("kotlin.CharArray"),
        "ByteArray" to ClassType("kotlin.ByteArray"),
        "ShortArray" to ClassType("kotlin.ShortArray"),

        "List" to ClassType.LIST,
        "MutableList" to ClassType("kotlin.collections.MutableList"),
        "Set" to ClassType.SET,
        "MutableSet" to ClassType("kotlin.collections.MutableSet"),
        "Map" to ClassType.MAP,
        "MutableMap" to ClassType("kotlin.collections.MutableMap"),
        "Collection" to ClassType.COLLECTION,
        "MutableCollection" to ClassType("kotlin.collections.MutableCollection"),
        "Iterable" to ClassType.ITERABLE,
        "MutableIterable" to ClassType("kotlin.collections.MutableIterable"),
        "Sequence" to ClassType.SEQUENCE,

        "Pair" to ClassType("kotlin.Pair"),
        "Triple" to ClassType("kotlin.Triple"),
        "Lazy" to ClassType("kotlin.Lazy"),
        "Result" to ClassType("kotlin.Result")
    )

    /**
     * Resolves a TypeReference to a KotlinType.
     *
     * @param ref The type reference from parsing
     * @param scope The scope for resolving type parameters
     * @param imports Optional list of imports for resolving non-qualified type names
     * @param indexLookup Optional function to validate FQ names against the index
     * @return The resolved KotlinType, or ErrorType if unresolved
     */
    fun resolve(
        ref: TypeReference,
        scope: Scope? = null,
        imports: List<ImportInfo> = emptyList(),
        indexLookup: ((String) -> IndexedSymbol?)? = null
    ): KotlinType {
        if (ref.isFunctionType && ref.functionTypeInfo != null) {
            val info = ref.functionTypeInfo
            val functionType = FunctionType(
                parameterTypes = info.parameterTypes.map { resolve(it, scope, imports, indexLookup) },
                returnType = resolve(info.returnType, scope, imports, indexLookup),
                receiverType = info.receiverType?.let { resolve(it, scope, imports, indexLookup) }
            )
            return if (ref.isNullable) functionType.nullable() else functionType
        }

        val baseType = resolveBaseName(ref.name, scope, imports, indexLookup)

        val withArgs = if (ref.typeArguments.isNotEmpty() && baseType is ClassType) {
            val resolvedArgs = ref.typeArguments.map { argRef ->
                TypeArgument.invariant(resolve(argRef, scope, imports, indexLookup))
            }
            baseType.copy(typeArguments = resolvedArgs)
        } else {
            baseType
        }

        return if (ref.isNullable) withArgs.nullable() else withArgs
    }

    /**
     * Resolves a simple type name.
     *
     * @param name The type name to resolve
     * @param scope The scope for resolving type parameters
     * @param imports List of imports for resolving non-qualified type names
     * @param indexLookup Optional function to validate FQ names against the index
     */
    fun resolveBaseName(
        name: String,
        scope: Scope? = null,
        imports: List<ImportInfo> = emptyList(),
        indexLookup: ((String) -> IndexedSymbol?)? = null
    ): KotlinType {
        val simpleName = name.substringAfterLast('.')

        primitiveNames[simpleName]?.let { return it }

        builtinTypes[simpleName]?.let { return it }

        if (name.startsWith("kotlin.")) {
            return ClassType(name)
        }

        if (scope != null) {
            val typeParam = scope.resolve(simpleName)
                .filterIsInstance<TypeParameterSymbol>()
                .firstOrNull()

            if (typeParam != null) {
                return TypeParameter(
                    name = typeParam.name,
                    bounds = typeParam.bounds.mapNotNull { resolve(it, scope) as? KotlinType },
                    symbol = typeParam
                )
            }
        }

        if ('.' in name) {
            if (indexLookup != null) {
                val symbol = indexLookup(name)
                if (symbol != null) return ClassType(name)
                return ErrorType.unresolved(name)
            }
            return ClassType(name)
        }

        for (import in imports) {
            if (import.isStar) continue
            val importedName = import.alias ?: import.fqName.substringAfterLast('.')
            if (importedName == simpleName) {
                if (indexLookup != null) {
                    val symbol = indexLookup(import.fqName)
                    if (symbol != null) return ClassType(import.fqName)
                    continue
                }
                return ClassType(import.fqName)
            }
        }

        if (indexLookup != null) {
            for (pkg in KOTLIN_AUTO_IMPORTS) {
                val candidateFqn = "$pkg.$simpleName"
                if (indexLookup(candidateFqn) != null) {
                    return ClassType(candidateFqn)
                }
            }
        }

        for (import in imports) {
            if (!import.isStar) continue
            val candidateFqn = "${import.fqName}.$simpleName"
            if (indexLookup != null) {
                val symbol = indexLookup(candidateFqn)
                if (symbol != null) return ClassType(candidateFqn)
                continue
            }
            return ClassType(candidateFqn)
        }

        if (indexLookup == null) {
            return ClassType("kotlin.$simpleName")
        }

        return ErrorType.unresolved(simpleName)
    }

    /**
     * Resolves a function type string like "(Int, String) -> Boolean".
     */
    fun resolveFunctionType(
        paramTypes: List<TypeReference>,
        returnType: TypeReference,
        receiverType: TypeReference? = null,
        isSuspend: Boolean = false,
        scope: Scope? = null
    ): FunctionType {
        return FunctionType(
            parameterTypes = paramTypes.map { resolve(it, scope) },
            returnType = resolve(returnType, scope),
            receiverType = receiverType?.let { resolve(it, scope) },
            isSuspend = isSuspend
        )
    }

    /**
     * Creates a List type with the given element type.
     */
    fun listOf(elementType: KotlinType): ClassType {
        return ClassType.LIST.withArguments(elementType)
    }

    /**
     * Creates a Set type with the given element type.
     */
    fun setOf(elementType: KotlinType): ClassType {
        return ClassType.SET.withArguments(elementType)
    }

    /**
     * Creates a Map type with the given key and value types.
     */
    fun mapOf(keyType: KotlinType, valueType: KotlinType): ClassType {
        return ClassType.MAP.copy(
            typeArguments = listOf(
                TypeArgument.invariant(keyType),
                TypeArgument.invariant(valueType)
            )
        )
    }

    /**
     * Creates an Array type with the given element type.
     */
    fun arrayOf(elementType: KotlinType): ClassType {
        return ClassType.ARRAY.withArguments(elementType)
    }

    /**
     * Creates a Comparable type with the given element type.
     */
    fun comparableOf(elementType: KotlinType): ClassType {
        return ClassType.COMPARABLE.withArguments(elementType)
    }

    companion object {
        val DEFAULT = TypeResolver()

        private val KOTLIN_AUTO_IMPORTS = listOf(
            "kotlin",
            "kotlin.collections",
            "kotlin.sequences",
            "kotlin.ranges",
            "kotlin.text",
            "kotlin.io",
            "kotlin.annotation",
            "kotlin.comparisons"
        )

        fun resolveSimple(name: String): KotlinType {
            return DEFAULT.resolveBaseName(name, null)
        }
    }
}

/**
 * Factory for creating common types.
 */
object Types {
    val INT = PrimitiveType.INT
    val LONG = PrimitiveType.LONG
    val FLOAT = PrimitiveType.FLOAT
    val DOUBLE = PrimitiveType.DOUBLE
    val BOOLEAN = PrimitiveType.BOOLEAN
    val CHAR = PrimitiveType.CHAR
    val BYTE = PrimitiveType.BYTE
    val SHORT = PrimitiveType.SHORT

    val ANY = ClassType.ANY
    val ANY_NULLABLE = ClassType.ANY_NULLABLE
    val UNIT = ClassType.UNIT
    val NOTHING = ClassType.NOTHING
    val STRING = ClassType.STRING
    val NUMBER = ClassType("kotlin.Number")

    fun nullable(type: KotlinType): KotlinType = type.nullable()

    fun list(elementType: KotlinType) = ClassType.LIST.withArguments(elementType)

    fun mutableList(elementType: KotlinType) =
        ClassType("kotlin.collections.MutableList").withArguments(elementType)

    fun set(elementType: KotlinType) = ClassType.SET.withArguments(elementType)

    fun mutableSet(elementType: KotlinType) =
        ClassType("kotlin.collections.MutableSet").withArguments(elementType)

    fun map(keyType: KotlinType, valueType: KotlinType) =
        ClassType.MAP.copy(typeArguments = listOf(
            TypeArgument.invariant(keyType),
            TypeArgument.invariant(valueType)
        ))

    fun mutableMap(keyType: KotlinType, valueType: KotlinType) =
        ClassType("kotlin.collections.MutableMap").copy(typeArguments = listOf(
            TypeArgument.invariant(keyType),
            TypeArgument.invariant(valueType)
        ))

    fun array(elementType: KotlinType) = ClassType.ARRAY.withArguments(elementType)

    fun function(vararg paramTypes: KotlinType, returnType: KotlinType) =
        FunctionType(paramTypes.toList(), returnType)

    fun suspendFunction(vararg paramTypes: KotlinType, returnType: KotlinType) =
        FunctionType(paramTypes.toList(), returnType, isSuspend = true)

    fun pair(first: KotlinType, second: KotlinType) =
        ClassType("kotlin.Pair").copy(typeArguments = listOf(
            TypeArgument.invariant(first),
            TypeArgument.invariant(second)
        ))

    fun triple(first: KotlinType, second: KotlinType, third: KotlinType) =
        ClassType("kotlin.Triple").copy(typeArguments = listOf(
            TypeArgument.invariant(first),
            TypeArgument.invariant(second),
            TypeArgument.invariant(third)
        ))
}

class ImportAwareTypeResolver(
    private val symbolTable: SymbolTable,
    private val projectIndex: ProjectIndex? = null
) {
    private val delegate = TypeResolver()

    private val indexLookup: ((String) -> IndexedSymbol?)? = projectIndex?.let { idx ->
        { fqName: String ->
            idx.findByFqName(fqName)
                ?: idx.getStdlibIndex()?.findByFqName(fqName)
                ?: idx.getClasspathIndex()?.findByFqName(fqName)
        }
    }

    fun resolve(ref: TypeReference, scope: Scope? = null): KotlinType {
        return delegate.resolve(ref, scope, symbolTable.imports, indexLookup)
    }

    fun resolveBaseName(name: String, scope: Scope? = null): KotlinType {
        return delegate.resolveBaseName(name, scope, symbolTable.imports, indexLookup)
    }

    fun resolveFunctionType(
        paramTypes: List<TypeReference>,
        returnType: TypeReference,
        receiverType: TypeReference? = null,
        isSuspend: Boolean = false,
        scope: Scope? = null
    ): FunctionType {
        return delegate.resolveFunctionType(paramTypes, returnType, receiverType, isSuspend, scope)
    }

    fun listOf(elementType: KotlinType) = delegate.listOf(elementType)
    fun setOf(elementType: KotlinType) = delegate.setOf(elementType)
    fun mapOf(keyType: KotlinType, valueType: KotlinType) = delegate.mapOf(keyType, valueType)
    fun arrayOf(elementType: KotlinType) = delegate.arrayOf(elementType)
    fun comparableOf(elementType: KotlinType) = delegate.comparableOf(elementType)
}