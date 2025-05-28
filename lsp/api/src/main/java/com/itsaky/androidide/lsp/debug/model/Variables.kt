package com.itsaky.androidide.lsp.debug.model

/**
 * The kind of [Variable].
 */
enum class VariableKind {

    /**
     * A primitive variable.
     */
    PRIMITIVE,

    /**
     * A string variable.
     */
    STRING,

    /**
     * An array-like variable with fixed size, known element types and indices.
     */
    ARRAYLIKE,

    /**
     * A reference variable.
     */
    REFERENCE,

    /**
     * An unknown variable.
     */
    UNKNOWN
}

/**
 * The kind of [PrimitiveValue].
 */
enum class PrimitiveKind {
    BOOLEAN,
    BYTE,
    CHAR,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE
}

/**
 * A value assigned to a [Variable].
 */
interface Value {

    /**
     * The value of the variable.
     */
    val value: Any
}

/**
 * A primitive value.
 */
interface PrimitiveValue: Value {

    /**
     * The [kind][PrimitiveKind] of the variable.
     */
    val kind: PrimitiveKind

    /**
     * The value of the variable as a [Byte].
     */
    fun asByte(): Byte

    /**
     * The value of the variable as a [Short].
     */
    fun asShort(): Short

    /**
     * The value of the variable as a [Int].
     */
    fun asInt(): Int

    /**
     * The value of the variable as a [Long].
     */
    fun asLong(): Long

    /**
     * The value of the variable as a [Float].
     */
    fun asFloat(): Float

    /**
     * The value of the variable as a [Double].
     */
    fun asDouble(): Double

    /**
     * The value of the variable as a [Boolean].
     */
    fun asBoolean(): Boolean

    /**
     * The value of the variable as a [Char].
     */
    fun asChar(): Char

    /**
     * The value of the variable as a [String].
     */
    fun asString(): String
}

/**
 * A string value.
 */
interface StringValue: Value {

    /**
     * The value of the variable as a [String].
     */
    fun asString(): String
}

/**
 * A reference value.
 */
interface ReferenceValue : Value {
    /**
     * Returns a string representation of the object.
     */
    override fun toString(): String
}

/**
 * An array-like value with fixed size, known element types and indices.
 */
interface ArrayLikeValue : Value {
    /**
     * The size of the array-like value.
     */
    val size: ULong

    /**
     * Whether the array-like value is empty.
     */
    val isEmpty: Boolean
        get() = size == 0UL

    /**
     * Get the value of the element at the given [index].
     */
    operator fun get(index: ULong): Value
}

/**
 * A descriptor of a [Variable].
 *
 * @property name The name of the variable.
 * @property typeName The type name of the variable.
 * @property kind The [kind][VariableKind] of the variable.
 * @property isMutable Whether the variable is mutable.
 */
data class VariableDescriptor(
    val name: String,
    val typeName: String,
    val kind: VariableKind,
    val isMutable: Boolean,
)

/**
 * Information about a local variable.
 */
interface Variable<ValueType: Value> {

    /**
     * Get the descriptor for the variable.
     */
    suspend fun descriptor(): VariableDescriptor

    /**
     * Get the value of the variable as [ValueType].
     */
    suspend fun value(): ValueType

    /**
     * Get the members of of the object that this variable references. May be empty.
     */
    suspend fun objectMembers(): Set<Variable<*>>
}

/**
 * A variable that has a primitive value.
 */
interface PrimitiveVariable: Variable<PrimitiveValue> {

    /**
     * The [primitive kind][PrimitiveKind] of the variable.
     */
    val primitiveKind: PrimitiveKind
}

/**
 * A variable that has a string value.
 */
interface StringVariable: Variable<StringValue>

/**
 * A variable that has an array-like value.
 */
interface ArrayLikeVariable: Variable<ArrayLikeValue>

/**
 * A variable that has a reference value.
 */
interface ReferenceVariable: Variable<ReferenceValue>