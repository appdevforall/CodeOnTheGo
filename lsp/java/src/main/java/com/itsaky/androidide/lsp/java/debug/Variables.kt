package com.itsaky.androidide.lsp.java.debug

import com.itsaky.androidide.lsp.debug.model.ArrayLikeValue
import com.itsaky.androidide.lsp.debug.model.PrimitiveKind
import com.itsaky.androidide.lsp.debug.model.PrimitiveVariable
import com.itsaky.androidide.lsp.debug.model.ReferenceValue
import com.itsaky.androidide.lsp.debug.model.StringVariable
import com.itsaky.androidide.lsp.debug.model.Variable
import com.itsaky.androidide.lsp.debug.model.VariableDescriptor
import com.itsaky.androidide.lsp.debug.model.VariableKind
import com.sun.jdi.ArrayReference
import com.sun.jdi.ArrayType
import com.sun.jdi.BooleanType
import com.sun.jdi.BooleanValue
import com.sun.jdi.ByteType
import com.sun.jdi.ByteValue
import com.sun.jdi.CharType
import com.sun.jdi.CharValue
import com.sun.jdi.DoubleType
import com.sun.jdi.DoubleValue
import com.sun.jdi.Field
import com.sun.jdi.FloatType
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerType
import com.sun.jdi.IntegerValue
import com.sun.jdi.LocalVariable
import com.sun.jdi.LongType
import com.sun.jdi.LongValue
import com.sun.jdi.Mirror
import com.sun.jdi.ObjectReference
import com.sun.jdi.PrimitiveType
import com.sun.jdi.PrimitiveValue
import com.sun.jdi.ReferenceType
import com.sun.jdi.ShortType
import com.sun.jdi.ShortValue
import com.sun.jdi.StackFrame
import com.sun.jdi.StringReference
import com.sun.jdi.Type
import com.sun.jdi.Value
import com.sun.jdi.VoidValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import com.itsaky.androidide.lsp.debug.model.PrimitiveValue as LspPrimitiveValue
import com.itsaky.androidide.lsp.debug.model.StringValue as LspStringValue
import com.itsaky.androidide.lsp.debug.model.Value as LspValue

private fun Value.toLspValue(): LspValue = when (this) {
    is PrimitiveValue -> JavaPrimitiveValue(this)
    is StringReference -> JavaStringValue(this)
    is ArrayReference -> JavaArrayLikeValue(this)
    else -> JavaReferenceValue(this)
}

private fun Mirror.mirrorOf(value: Boolean): BooleanValue = virtualMachine().mirrorOf(value)
private fun Mirror.mirrorOf(value: Byte): ByteValue = virtualMachine().mirrorOf(value)
private fun Mirror.mirrorOf(value: Char): CharValue = virtualMachine().mirrorOf(value)
private fun Mirror.mirrorOf(value: Short): ShortValue = virtualMachine().mirrorOf(value)
private fun Mirror.mirrorOf(value: Int): IntegerValue = virtualMachine().mirrorOf(value)
private fun Mirror.mirrorOf(value: Long): LongValue = virtualMachine().mirrorOf(value)
private fun Mirror.mirrorOf(value: Float): FloatValue = virtualMachine().mirrorOf(value)
private fun Mirror.mirrorOf(value: Double): DoubleValue = virtualMachine().mirrorOf(value)
private fun Mirror.mirrorOf(value: String): StringReference = virtualMachine().mirrorOf(value)
private fun Mirror.void(): VoidValue = virtualMachine().mirrorOfVoid()

sealed class BaseJavaValue(
    override val value: Any
) : LspValue

internal class JavaPrimitiveValue(
    private val primitive: PrimitiveValue
) : BaseJavaValue(primitive), LspPrimitiveValue {

    override val kind: PrimitiveKind by lazy {
        when (primitive.type()) {
            is ByteType -> PrimitiveKind.BYTE
            is CharType -> PrimitiveKind.CHAR
            is ShortType -> PrimitiveKind.SHORT
            is IntegerType -> PrimitiveKind.INT
            is LongType -> PrimitiveKind.LONG
            is FloatType -> PrimitiveKind.FLOAT
            is DoubleType -> PrimitiveKind.DOUBLE
            is BooleanType -> PrimitiveKind.BOOLEAN
            else -> throw IllegalStateException("Unsupported primitive type: ${primitive.type()}")
        }
    }

    override fun asByte(): Byte = primitive.byteValue()
    override fun asShort(): Short = primitive.shortValue()
    override fun asInt(): Int = primitive.intValue()
    override fun asLong(): Long = primitive.longValue()
    override fun asFloat(): Float = primitive.floatValue()
    override fun asDouble(): Double = primitive.doubleValue()
    override fun asBoolean(): Boolean = primitive.booleanValue()
    override fun asChar(): Char = primitive.charValue()
    override fun asString(): String = primitive.toString()
    override fun toString(): String = asString()
}

internal class JavaStringValue(
    private val jdi: StringReference
) : BaseJavaValue(jdi), LspStringValue {
    override fun asString(): String = jdi.value()
    override fun toString(): String = asString()
}

internal class JavaReferenceValue(
    private val jdi: Value
) : BaseJavaValue(jdi), ReferenceValue {

    override fun toString(): String = jdi.toString()
}

internal class JavaArrayLikeValue(
    private val jdi: ArrayReference
) : BaseJavaValue(jdi), ArrayLikeValue {

    override val size: ULong
        get() = jdi.length().toULong()

    override fun get(index: ULong): LspValue = jdi.getValue(index.toInt()).toLspValue()
}

internal abstract class AbstractJavaVariable<ValueT : LspValue>(
    protected val name: String,
    protected val typeName: String,
    protected val type: Type,
) : Variable<ValueT> {

    internal val kind by lazy {
        when (type) {
            is PrimitiveType -> VariableKind.PRIMITIVE

            // TODO: Support other array-like types (like lists).
            is ArrayType -> VariableKind.ARRAYLIKE
            is ObjectReference -> VariableKind.REFERENCE
            else -> VariableKind.UNKNOWN
        }
    }

    protected abstract suspend fun jdiValue(): Value
    protected open suspend fun isMutable(): Boolean = false

    suspend fun resolveKind(): VariableKind {
        return try {
            val valInstance = jdiValue()
            val type = valInstance.type()

            println("_____ resolveKind → name: $name, runtimeValue.class: ${valInstance.javaClass.name}, jdiType: ${type.name()}")

            when (valInstance) {
                is com.sun.jdi.PrimitiveValue -> VariableKind.PRIMITIVE
                is com.sun.jdi.StringReference -> VariableKind.STRING
                is com.sun.jdi.ArrayReference -> VariableKind.ARRAYLIKE

                is com.sun.jdi.ObjectReference -> {
                    val refType = type as? ReferenceType
                    val methodNames = refType?.methods()?.map { it.name() }?.toSet().orEmpty()

                    val isBoxedPrimitive = methodNames.any { it.matches(Regex("^(boolean|byte|short|char|int|long|float|double)Value$")) }

                    println("_____ resolveKind → methods: $methodNames, isBoxedPrimitive: $isBoxedPrimitive")

                    if (isBoxedPrimitive) VariableKind.PRIMITIVE else VariableKind.REFERENCE
                }

                else -> VariableKind.UNKNOWN
            }
        } catch (e: Exception) {
            println("_____ resolveKind → ERROR: ${e.message}")
            VariableKind.UNKNOWN
        }
    }

    override suspend fun descriptor(): VariableDescriptor {
        val resolvedKind = resolveKind()
        return VariableDescriptor(
            name = name,
            typeName = typeName,
            kind = resolvedKind,
            isMutable = isMutable()
        )
    }

    override suspend fun objectMembers(): Set<Variable<*>> = withContext(Dispatchers.IO) {
        val value = jdiValue()
        val type = value.type()
        if (value !is ObjectReference) {
            // TODO: We can provide other information as 'members' for other types of variables.
            return@withContext emptySet()
        }

        type as ReferenceType

        val fields = type.allFields()
        fields.map { field ->
            JavaFieldVariable<ValueT>(value, type, field)
        }.toSet()
    }
}

internal class JavaFieldVariable<ValueT : LspValue>(
    private val ref: ObjectReference,
    private val refType: ReferenceType,
    private val field: Field,
) : AbstractJavaVariable<ValueT>(
    name = field.name(), typeName = field.typeName(), type = field.type()
) {

    override suspend fun isMutable(): Boolean = !field.isFinal
    override suspend fun jdiValue(): Value = ref

    @Suppress("UNCHECKED_CAST")
    override suspend fun value(): ValueT = withContext(Dispatchers.IO) {
        (if (field.isStatic) refType.getValue(field) else ref.getValue(field)).toLspValue() as ValueT
    }
}

internal abstract class JavaLocalVariable<ValueType : LspValue>(
    protected val stackFrame: StackFrame,
    protected val variable: LocalVariable,
) : AbstractJavaVariable<ValueType>(
    name = variable.name(), typeName = variable.typeName(), type = variable.type()
) {

    companion object {
        private val logger = LoggerFactory.getLogger(JavaLocalVariable::class.java)
        internal fun forVariable(
            stackFrame: StackFrame, variable: LocalVariable
        ): JavaLocalVariable<*> = when (variable.type()) {
            is PrimitiveType -> JavaPrimitiveVariable(stackFrame, variable)
            else -> JavaStringVariable(stackFrame, variable)
        }
    }

    override suspend fun isMutable(): Boolean = true

    @Suppress("UNCHECKED_CAST")
    override suspend fun value(): ValueType? = withContext(Dispatchers.IO) {
        try {
            // TODO: find out if we can distinguish between opaque and non-opaque stack frames
            stackFrame.getValue(variable).toLspValue() as ValueType
        } catch (e: Exception) {
            logger.error(
                "Failed to get value of variable '{}' in {}",
                variable.name(),
                stackFrame.location().method(),
                e
            )
            null
        }
    }

    override suspend fun jdiValue(): Value = stackFrame.getValue(variable)

    protected inline fun <reified T : Type> requireType(action: () -> Unit) {
        check(type is T) {
            "Variable $name is not of type ${T::class.simpleName}"
        }
        action()
    }

    protected fun setValue(value: Value) = stackFrame.setValue(variable, value)

    override suspend fun objectMembers(): Set<Variable<*>> {
        return super.objectMembers()
    }
}

internal class JavaPrimitiveVariable(
    stackFrame: StackFrame,
    variable: LocalVariable,
) : JavaLocalVariable<LspPrimitiveValue>(stackFrame, variable), PrimitiveVariable {

    override val primitiveKind: PrimitiveKind by lazy {
        when (type) {
            is ByteType -> PrimitiveKind.BYTE
            is CharType -> PrimitiveKind.CHAR
            is ShortType -> PrimitiveKind.SHORT
            is IntegerType -> PrimitiveKind.INT
            is LongType -> PrimitiveKind.LONG
            is FloatType -> PrimitiveKind.FLOAT
            is DoubleType -> PrimitiveKind.DOUBLE
            is BooleanType -> PrimitiveKind.BOOLEAN
            else -> throw IllegalStateException("Unsupported primitive type: $type")
        }
    }

    internal fun setValue(boolean: Boolean) = requireType<ByteType> {
        setValue(variable.mirrorOf(boolean))
    }

    internal fun setValue(byte: Byte) = requireType<ByteType> {
        setValue(variable.mirrorOf(byte))
    }

    internal fun setValue(short: Short) = requireType<ByteType> {
        setValue(variable.mirrorOf(short))
    }

    internal fun setValue(char: Char) = requireType<ByteType> {
        setValue(variable.mirrorOf(char))
    }

    internal fun setValue(int: Int) = requireType<ByteType> {
        setValue(variable.mirrorOf(int))
    }

    internal fun setValue(long: Long) = requireType<ByteType> {
        setValue(variable.mirrorOf(long))
    }

    internal fun setValue(float: Float) = requireType<ByteType> {
        setValue(variable.mirrorOf(float))
    }

    internal fun setValue(double: Double) = requireType<ByteType> {
        setValue(variable.mirrorOf(double))
    }
}

internal class JavaStringVariable(
    stackFrame: StackFrame,
    variable: LocalVariable,
) : JavaLocalVariable<LspStringValue>(stackFrame, variable), StringVariable {
    internal fun setValue(string: String) = requireType<ByteType> {
        setValue(variable.mirrorOf(string))
    }
}
