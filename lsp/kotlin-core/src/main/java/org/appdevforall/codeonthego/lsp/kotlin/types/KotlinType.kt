package org.appdevforall.codeonthego.lsp.kotlin.types

import org.appdevforall.codeonthego.lsp.kotlin.symbol.ClassSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeParameterSymbol

/**
 * Base class for all Kotlin types.
 *
 * Kotlin's type system includes:
 * - Primitive types: Int, Long, Float, Double, Boolean, Char, Byte, Short
 * - Class types: String, List<T>, custom classes
 * - Function types: (Int) -> String, suspend () -> Unit
 * - Nullable types: String?, Int?
 * - Type parameters: T, in T, out T
 * - Special types: Unit, Nothing, Any, Any?
 *
 * ## Nullability
 *
 * All types support nullability through [nullable] and [nonNullable] methods.
 * The [isNullable] property indicates whether null is a valid value.
 *
 * ## Type Parameters
 *
 * Generic types use [TypeArgument] for type arguments. Type parameters
 * can be substituted using [substitute].
 *
 * ## Example
 *
 * ```kotlin
 * val stringType = ClassType.of("kotlin.String")
 * val nullableString = stringType.nullable()
 * val listOfStrings = ClassType.of("kotlin.collections.List", stringType)
 * ```
 *
 * @see ClassType
 * @see FunctionType
 * @see TypeParameter
 */
sealed class KotlinType {

    /**
     * Whether this type is nullable (allows null values).
     */
    abstract val isNullable: Boolean

    /**
     * Whether this is a type parameter (unbound generic).
     */
    open val isTypeParameter: Boolean get() = false

    /**
     * Whether this is the Nothing type.
     */
    open val isNothing: Boolean get() = false

    /**
     * Whether this is the Unit type.
     */
    open val isUnit: Boolean get() = false

    /**
     * Whether this is the Any type.
     */
    open val isAny: Boolean get() = false

    /**
     * Whether this type contains errors (unresolved references).
     */
    open val hasError: Boolean get() = false

    /**
     * Returns a nullable version of this type.
     */
    abstract fun nullable(): KotlinType

    /**
     * Returns a non-nullable version of this type.
     */
    abstract fun nonNullable(): KotlinType

    /**
     * Substitutes type parameters with concrete types.
     *
     * @param substitution Map from type parameters to their replacements
     * @return The type with substitutions applied
     */
    abstract fun substitute(substitution: TypeSubstitution): KotlinType

    /**
     * Renders this type as a readable string.
     *
     * @param qualified Whether to use fully qualified names
     * @return String representation of the type
     */
    abstract fun render(qualified: Boolean = false): String

    /**
     * Returns all type parameters used in this type.
     */
    abstract fun typeParameters(): Set<TypeParameter>

    /**
     * Checks if this type mentions a specific type parameter.
     */
    fun mentionsTypeParameter(param: TypeParameter): Boolean {
        return param in typeParameters()
    }

    override fun toString(): String = render()
}

/**
 * A class or interface type, optionally with type arguments.
 *
 * Represents types like:
 * - `String`
 * - `List<Int>`
 * - `Map<String, List<Int>>`
 * - `Comparable<T>`
 *
 * @property fqName Fully qualified name (e.g., "kotlin.String")
 * @property typeArguments Type arguments for generic types
 * @property symbol The resolved class symbol (if available)
 */
data class ClassType(
    val fqName: String,
    val typeArguments: List<TypeArgument> = emptyList(),
    override val isNullable: Boolean = false,
    val symbol: ClassSymbol? = null
) : KotlinType() {

    /**
     * The simple name (without package).
     */
    val simpleName: String get() = fqName.substringAfterLast('.')

    /**
     * Whether this is a generic type.
     */
    val isGeneric: Boolean get() = typeArguments.isNotEmpty()

    /**
     * The number of type arguments.
     */
    val arity: Int get() = typeArguments.size

    override val isAny: Boolean get() = fqName == "kotlin.Any"
    override val isUnit: Boolean get() = fqName == "kotlin.Unit"
    override val isNothing: Boolean get() = fqName == "kotlin.Nothing"

    override fun nullable(): ClassType = copy(isNullable = true)
    override fun nonNullable(): ClassType = copy(isNullable = false)

    override fun substitute(substitution: TypeSubstitution): KotlinType {
        if (typeArguments.isEmpty()) return this

        val newArgs = typeArguments.map { it.substitute(substitution) }
        return if (newArgs == typeArguments) this else copy(typeArguments = newArgs)
    }

    override fun render(qualified: Boolean): String = buildString {
        append(if (qualified) fqName else simpleName)
        if (typeArguments.isNotEmpty()) {
            append('<')
            typeArguments.joinTo(this) { it.render(qualified) }
            append('>')
        }
        if (isNullable) append('?')
    }

    override fun typeParameters(): Set<TypeParameter> {
        return typeArguments.flatMap { it.type?.typeParameters() ?: emptySet() }.toSet()
    }

    /**
     * Creates a type with the given type arguments.
     */
    fun withArguments(vararg args: KotlinType): ClassType {
        return copy(typeArguments = args.map { TypeArgument.invariant(it) })
    }

    companion object {
        fun of(fqName: String, nullable: Boolean = false): ClassType {
            return ClassType(fqName, isNullable = nullable)
        }

        fun of(fqName: String, vararg typeArgs: KotlinType): ClassType {
            return ClassType(fqName, typeArgs.map { TypeArgument.invariant(it) })
        }

        val ANY = ClassType("kotlin.Any")
        val ANY_NULLABLE = ClassType("kotlin.Any", isNullable = true)
        val UNIT = ClassType("kotlin.Unit")
        val NOTHING = ClassType("kotlin.Nothing")
        val STRING = ClassType("kotlin.String")
        val CHAR_SEQUENCE = ClassType("kotlin.CharSequence")
        val COMPARABLE = ClassType("kotlin.Comparable")
        val ITERABLE = ClassType("kotlin.collections.Iterable")
        val COLLECTION = ClassType("kotlin.collections.Collection")
        val LIST = ClassType("kotlin.collections.List")
        val SET = ClassType("kotlin.collections.Set")
        val MAP = ClassType("kotlin.collections.Map")
        val SEQUENCE = ClassType("kotlin.sequences.Sequence")
        val ARRAY = ClassType("kotlin.Array")
        val THROWABLE = ClassType("kotlin.Throwable")
        val EXCEPTION = ClassType("kotlin.Exception")
    }
}

/**
 * A primitive type.
 *
 * Kotlin's primitive types are:
 * - Numeric: Byte, Short, Int, Long, Float, Double
 * - Boolean: Boolean
 * - Character: Char
 *
 * On the JVM, these map to Java primitive types when not nullable.
 */
data class PrimitiveType(
    val kind: PrimitiveKind,
    override val isNullable: Boolean = false
) : KotlinType() {

    val fqName: String get() = "kotlin.${kind.name.lowercase().replaceFirstChar { it.uppercase() }}"

    override fun nullable(): PrimitiveType = copy(isNullable = true)
    override fun nonNullable(): PrimitiveType = copy(isNullable = false)

    override fun substitute(substitution: TypeSubstitution): KotlinType = this

    override fun render(qualified: Boolean): String = buildString {
        append(kind.name.lowercase().replaceFirstChar { it.uppercase() })
        if (isNullable) append('?')
    }

    override fun typeParameters(): Set<TypeParameter> = emptySet()

    companion object {
        val BYTE = PrimitiveType(PrimitiveKind.BYTE)
        val SHORT = PrimitiveType(PrimitiveKind.SHORT)
        val INT = PrimitiveType(PrimitiveKind.INT)
        val LONG = PrimitiveType(PrimitiveKind.LONG)
        val FLOAT = PrimitiveType(PrimitiveKind.FLOAT)
        val DOUBLE = PrimitiveType(PrimitiveKind.DOUBLE)
        val BOOLEAN = PrimitiveType(PrimitiveKind.BOOLEAN)
        val CHAR = PrimitiveType(PrimitiveKind.CHAR)

        fun fromName(name: String): PrimitiveType? {
            val kind = PrimitiveKind.entries.find {
                it.name.equals(name, ignoreCase = true)
            } ?: return null
            return PrimitiveType(kind)
        }
    }
}

/**
 * Kind of primitive type.
 */
enum class PrimitiveKind {
    BYTE,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    BOOLEAN,
    CHAR;

    val isNumeric: Boolean get() = this != BOOLEAN && this != CHAR
    val isIntegral: Boolean get() = this in listOf(BYTE, SHORT, INT, LONG)
    val isFloatingPoint: Boolean get() = this == FLOAT || this == DOUBLE

    val bitWidth: Int get() = when (this) {
        BYTE -> 8
        SHORT, CHAR -> 16
        INT, FLOAT -> 32
        LONG, DOUBLE -> 64
        BOOLEAN -> 1
    }
}

/**
 * A function type.
 *
 * Represents types like:
 * - `() -> Unit`
 * - `(Int) -> String`
 * - `(Int, String) -> Boolean`
 * - `suspend () -> Unit`
 * - `Int.() -> String` (extension function type)
 *
 * @property parameterTypes Types of function parameters
 * @property returnType Return type of the function
 * @property receiverType Receiver type for extension function types
 * @property isSuspend Whether this is a suspend function type
 */
data class FunctionType(
    val parameterTypes: List<KotlinType>,
    val returnType: KotlinType,
    val receiverType: KotlinType? = null,
    val isSuspend: Boolean = false,
    override val isNullable: Boolean = false
) : KotlinType() {

    /**
     * Number of parameters (not including receiver).
     */
    val arity: Int get() = parameterTypes.size

    /**
     * Whether this is an extension function type.
     */
    val isExtension: Boolean get() = receiverType != null

    override fun nullable(): FunctionType = copy(isNullable = true)
    override fun nonNullable(): FunctionType = copy(isNullable = false)

    override fun substitute(substitution: TypeSubstitution): KotlinType {
        val newParams = parameterTypes.map { it.substitute(substitution) }
        val newReturn = returnType.substitute(substitution)
        val newReceiver = receiverType?.substitute(substitution)

        return if (newParams == parameterTypes && newReturn == returnType && newReceiver == receiverType) {
            this
        } else {
            copy(
                parameterTypes = newParams,
                returnType = newReturn,
                receiverType = newReceiver
            )
        }
    }

    override fun render(qualified: Boolean): String = buildString {
        if (isSuspend) append("suspend ")
        if (receiverType != null) {
            append(receiverType.render(qualified))
            append('.')
        }
        append('(')
        parameterTypes.joinTo(this) { it.render(qualified) }
        append(") -> ")
        append(returnType.render(qualified))
        if (isNullable) {
            insert(0, '(')
            append(")?")
        }
    }

    override fun typeParameters(): Set<TypeParameter> {
        val params = mutableSetOf<TypeParameter>()
        parameterTypes.forEach { params.addAll(it.typeParameters()) }
        params.addAll(returnType.typeParameters())
        receiverType?.let { params.addAll(it.typeParameters()) }
        return params
    }

    companion object {
        fun of(vararg paramTypes: KotlinType, returnType: KotlinType): FunctionType {
            return FunctionType(paramTypes.toList(), returnType)
        }

        fun suspend(vararg paramTypes: KotlinType, returnType: KotlinType): FunctionType {
            return FunctionType(paramTypes.toList(), returnType, isSuspend = true)
        }
    }
}

/**
 * A type parameter (generic type variable).
 *
 * Represents unbound type variables like `T` in `class Box<T>`.
 *
 * @property name The name of the type parameter
 * @property bounds Upper bounds (default is Any?)
 * @property variance Variance modifier (in/out/invariant)
 * @property symbol The resolved symbol (if available)
 */
data class TypeParameter(
    val name: String,
    val bounds: List<KotlinType> = emptyList(),
    val variance: TypeVariance = TypeVariance.INVARIANT,
    override val isNullable: Boolean = false,
    val symbol: TypeParameterSymbol? = null
) : KotlinType() {

    override val isTypeParameter: Boolean get() = true

    /**
     * The effective upper bound (first bound or Any?).
     */
    val effectiveBound: KotlinType
        get() = bounds.firstOrNull() ?: ClassType.ANY_NULLABLE

    /**
     * Whether this type parameter has explicit bounds.
     */
    val hasBounds: Boolean get() = bounds.isNotEmpty()

    override fun nullable(): TypeParameter = copy(isNullable = true)
    override fun nonNullable(): TypeParameter = copy(isNullable = false)

    override fun substitute(substitution: TypeSubstitution): KotlinType {
        val replacement = substitution[this]
        return if (replacement != null) {
            if (isNullable) replacement.nullable() else replacement
        } else {
            this
        }
    }

    override fun render(qualified: Boolean): String = buildString {
        when (variance) {
            TypeVariance.IN -> append("in ")
            TypeVariance.OUT -> append("out ")
            TypeVariance.INVARIANT -> {}
        }
        append(name)
        if (isNullable) append('?')
    }

    override fun typeParameters(): Set<TypeParameter> = setOf(this)

    companion object {
        fun of(name: String): TypeParameter = TypeParameter(name)

        fun withBound(name: String, bound: KotlinType): TypeParameter {
            return TypeParameter(name, listOf(bound))
        }
    }
}

/**
 * Variance for type parameters and arguments.
 */
enum class TypeVariance {
    INVARIANT,
    IN,
    OUT;

    companion object {
        fun fromKeyword(keyword: String): TypeVariance = when (keyword) {
            "in" -> IN
            "out" -> OUT
            else -> INVARIANT
        }
    }
}

/**
 * A type argument in a generic type.
 *
 * Can be:
 * - An actual type: `List<String>` has type argument `String`
 * - A projection: `List<out T>`, `List<in T>`
 * - A star projection: `List<*>`
 *
 * @property type The actual type (null for star projection)
 * @property variance Variance for projections
 */
data class TypeArgument(
    val type: KotlinType?,
    val variance: TypeVariance = TypeVariance.INVARIANT
) {
    /**
     * Whether this is a star projection (*).
     */
    val isStarProjection: Boolean get() = type == null

    /**
     * Whether this is a projection (in T, out T).
     */
    val isProjection: Boolean get() = variance != TypeVariance.INVARIANT

    fun substitute(substitution: TypeSubstitution): TypeArgument {
        if (type == null) return this
        val newType = type.substitute(substitution)
        return if (newType === type) this else copy(type = newType)
    }

    fun render(qualified: Boolean = false): String = when {
        isStarProjection -> "*"
        variance == TypeVariance.IN -> "in ${type!!.render(qualified)}"
        variance == TypeVariance.OUT -> "out ${type!!.render(qualified)}"
        else -> type!!.render(qualified)
    }

    override fun toString(): String = render()

    companion object {
        val STAR = TypeArgument(null)

        fun invariant(type: KotlinType) = TypeArgument(type, TypeVariance.INVARIANT)
        fun covariant(type: KotlinType) = TypeArgument(type, TypeVariance.OUT)
        fun contravariant(type: KotlinType) = TypeArgument(type, TypeVariance.IN)
    }
}

/**
 * An error/unknown type for unresolved references.
 */
data class ErrorType(
    val message: String = "Unresolved type",
    override val isNullable: Boolean = false
) : KotlinType() {

    override val hasError: Boolean get() = true

    override fun nullable(): ErrorType = copy(isNullable = true)
    override fun nonNullable(): ErrorType = copy(isNullable = false)

    override fun substitute(substitution: TypeSubstitution): KotlinType = this

    override fun render(qualified: Boolean): String = "<ERROR: $message>"

    override fun typeParameters(): Set<TypeParameter> = emptySet()

    companion object {
        val UNRESOLVED = ErrorType("Unresolved type")

        fun unresolved(name: String) = ErrorType("Unresolved type: $name")
    }
}

/**
 * Map from type parameters to their replacement types.
 */
class TypeSubstitution private constructor(
    private val map: Map<TypeParameter, KotlinType>
) {
    operator fun get(param: TypeParameter): KotlinType? = map[param]

    operator fun contains(param: TypeParameter): Boolean = param in map

    val isEmpty: Boolean get() = map.isEmpty()

    val entries: Set<Map.Entry<TypeParameter, KotlinType>> get() = map.entries

    fun with(param: TypeParameter, type: KotlinType): TypeSubstitution {
        return TypeSubstitution(map + (param to type))
    }

    fun compose(other: TypeSubstitution): TypeSubstitution {
        val newMap = map.mapValues { (_, type) -> type.substitute(other) } + other.map
        return TypeSubstitution(newMap)
    }

    companion object {
        val EMPTY = TypeSubstitution(emptyMap())

        fun of(vararg pairs: Pair<TypeParameter, KotlinType>): TypeSubstitution {
            return TypeSubstitution(pairs.toMap())
        }

        fun from(params: List<TypeParameter>, args: List<KotlinType>): TypeSubstitution {
            require(params.size == args.size) {
                "Parameter count ${params.size} doesn't match argument count ${args.size}"
            }
            return TypeSubstitution(params.zip(args).toMap())
        }
    }
}
