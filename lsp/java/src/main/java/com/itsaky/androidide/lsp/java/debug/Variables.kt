package com.itsaky.androidide.lsp.java.debug

import com.itsaky.androidide.lsp.debug.model.ArrayLikeValue
import com.itsaky.androidide.lsp.debug.model.PrimitiveKind
import com.itsaky.androidide.lsp.debug.model.PrimitiveVariable
import com.itsaky.androidide.lsp.debug.model.ReferenceValue
import com.itsaky.androidide.lsp.debug.model.StringVariable
import com.itsaky.androidide.lsp.debug.model.Variable
import com.itsaky.androidide.lsp.debug.model.VariableDescriptor
import com.itsaky.androidide.lsp.debug.model.VariableKind
import com.itsaky.androidide.lsp.java.debug.utils.mirrorOf
import com.itsaky.androidide.lsp.java.debug.utils.parseValue
import com.sun.jdi.ArrayReference
import com.sun.jdi.ArrayType
import com.sun.jdi.BooleanType
import com.sun.jdi.ByteType
import com.sun.jdi.CharType
import com.sun.jdi.DoubleType
import com.sun.jdi.Field
import com.sun.jdi.FloatType
import com.sun.jdi.IntegerType
import com.sun.jdi.InternalException
import com.sun.jdi.InvalidStackFrameException
import com.sun.jdi.LocalVariable
import com.sun.jdi.LongType
import com.sun.jdi.ObjectReference
import com.sun.jdi.PrimitiveType
import com.sun.jdi.PrimitiveValue
import com.sun.jdi.ReferenceType
import com.sun.jdi.ShortType
import com.sun.jdi.StackFrame
import com.sun.jdi.StringReference
import com.sun.jdi.Type
import com.sun.jdi.Value
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

    override suspend fun descriptor(): VariableDescriptor {
        return VariableDescriptor(
            name = name,
            typeName = typeName,
            kind = kind,
            isMutable = isMutable(),
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
    name = field.name(),
    typeName = field.typeName(),
    type = field.type()
) {

    companion object {
        private val logger = LoggerFactory.getLogger(JavaFieldVariable::class.java)
    }

    override suspend fun isMutable(): Boolean = !field.isFinal
    override suspend fun jdiValue(): Value = ref

    @Suppress("UNCHECKED_CAST")
    override suspend fun value(): ValueT = withContext(Dispatchers.IO) {
        (if (field.isStatic) refType.getValue(field) else ref.getValue(field)).toLspValue() as ValueT
    }

    override suspend fun setValue(value: String): Boolean {
        val newValue = this.field.parseValue(type, value) ?: run {
            logger.error("Failed to parse value '{}' for variable '{}'", value, name)
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                ref.setValue(field, newValue)
                true
            } catch (err: Throwable) {
                logger.error("Failed to set value of variable '{}'", name, err)
                false
            }
        }
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
            if (!stackFrame.thread().isSuspended) {
                logger.warn("Thread is not suspended; cannot access variable '{}'", variable.name())
                return@withContext null
            }
            val rawValue = stackFrame.getValue(variable)
            rawValue?.let {
                it.toLspValue() as ValueType
            }
        } catch (e: InternalException) {
            if (e.message?.contains("JDWP Error: 32") == true) {
                logger.warn("JDWP Error 32: Invalid frame when accessing variable '{}'", variable.name())
            } else {
                logger.error("Unexpected JDWP error when accessing '{}'", variable.name(), e)
            }
            null
        } catch (e: InvalidStackFrameException) {
            logger.warn("Stack frame invalid or no longer available for variable '{}'", variable.name())
            null
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

    override suspend fun setValue(value: String): Boolean {
        val newValue = this.variable.parseValue(type, value) ?: run {
            logger.error("Failed to parse value '{}' for variable '{}'", value, name)
            return false
        }

        return try {
            setValue(newValue)
            true
        } catch (err: Throwable) {
            logger.error("Failed to set value of variable '{}'", name, err)
            false
        }
    }

    protected suspend fun setValue(value: Value) = withContext(Dispatchers.IO) {
        stackFrame.setValue(variable, value)
    }

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

    internal suspend fun doSetValue(boolean: Boolean) = requireType<ByteType> {
        setValue(variable.mirrorOf(boolean))
    }

    internal suspend fun doSetValue(byte: Byte) = requireType<ByteType> {
        setValue(variable.mirrorOf(byte))
    }

    internal suspend fun doSetValue(short: Short) = requireType<ByteType> {
        setValue(variable.mirrorOf(short))
    }

    internal suspend fun doSetValue(char: Char) = requireType<ByteType> {
        setValue(variable.mirrorOf(char))
    }

    internal suspend fun doSetValue(int: Int) = requireType<ByteType> {
        setValue(variable.mirrorOf(int))
    }

    internal suspend fun doSetValue(long: Long) = requireType<ByteType> {
        setValue(variable.mirrorOf(long))
    }

    internal suspend fun doSetValue(float: Float) = requireType<ByteType> {
        setValue(variable.mirrorOf(float))
    }

    internal suspend fun doSetValue(double: Double) = requireType<ByteType> {
        setValue(variable.mirrorOf(double))
    }
}

internal class JavaStringVariable(
    stackFrame: StackFrame,
    variable: LocalVariable,
) : JavaLocalVariable<LspStringValue>(stackFrame, variable), StringVariable {
    internal suspend fun doSetValue(string: String) = requireType<ByteType> {
        setValue(variable.mirrorOf(string))
    }
}
