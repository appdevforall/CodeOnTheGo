package com.itsaky.androidide.lsp.java.debug

import com.itsaky.androidide.lsp.debug.model.ArrayLikeValue
import com.itsaky.androidide.lsp.debug.model.PrimitiveKind
import com.itsaky.androidide.lsp.debug.model.PrimitiveVariable
import com.itsaky.androidide.lsp.debug.model.Variable
import com.itsaky.androidide.lsp.debug.model.VariableDescriptor
import com.itsaky.androidide.lsp.debug.model.VariableKind
import com.itsaky.androidide.lsp.java.debug.utils.VariableValues
import com.itsaky.androidide.lsp.java.debug.utils.mirrorOf
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
import com.sun.jdi.ThreadReference
import com.sun.jdi.Type
import com.sun.jdi.Value
import com.sun.jdi.VoidValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import com.itsaky.androidide.lsp.debug.model.PrimitiveValue as LspPrimitiveValue
import com.itsaky.androidide.lsp.debug.model.ReferenceValue as LspReferenceValue
import com.itsaky.androidide.lsp.debug.model.Value as LspValue

private suspend fun toLspValue(thread: ThreadReference, value: Value): LspValue = when (value) {
    is PrimitiveValue -> JavaPrimitiveValue.create(value)
    is VoidValue -> JavaVoidValue.create(value)
    is ArrayReference -> JavaArrayLikeValue.create(thread, value)
    is ObjectReference -> JavaReferenceValue.create(thread, value)
    else -> throw IllegalArgumentException("Unsupported value type: $value")
}

sealed class BaseJavaValue(
    override val value: Any
) : LspValue

internal class JavaVoidValue private constructor(
    void: VoidValue
) : BaseJavaValue(void) {
    companion object {
        fun create(void: VoidValue) = JavaVoidValue(void)
    }

    override fun toString(): String = "void"
}

internal class JavaPrimitiveValue private constructor(
    private val primitive: PrimitiveValue
) : BaseJavaValue(primitive), LspPrimitiveValue {

    companion object {
        fun create(primitive: PrimitiveValue) = JavaPrimitiveValue(primitive)
    }

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

internal class JavaReferenceValue private constructor(
    ref: ObjectReference,
    private val stringRepr: String,
) : BaseJavaValue(ref), LspReferenceValue {

    companion object {
        private val logger = LoggerFactory.getLogger(JavaReferenceValue::class.java)

        suspend fun create(thread: ThreadReference, ref: ObjectReference): JavaReferenceValue {
            val str = runCatching {
                if (ref is StringReference) {
                    return@runCatching ref.value()
                }

                // TODO: Call toString() on reference to get its String representation
                //    However, if we try to invoke any method on the reference, the remote VM will resume
                //    the thread, which may result in other variable resolutions to fail because the
                //    thread would been resumed

                val invocationContext = JavaDebugAdapter.requireInstance()
                    .evalContext()

                val result = invocationContext.evaluate(thread) {
                    val type = ref.referenceType()
                    val method = type.methodsByName(
                        "toString",
                        "()Ljava/lang/String;",
                    ).firstOrNull() ?: return@evaluate null

                    ref.invokeMethod(
                        thread,
                        method,
                        emptyList(),
                        ObjectReference.INVOKE_SINGLE_THREADED
                    )
                }

                (result as? StringReference?)?.value() ?: ref.toString()

//                val type = ref.referenceType()
//                val method = type.methodsByName("toString", "()Ljava/lang/String;")
//                    .firstOrNull() ?: run {
//                    logger.error("No toString method found for type {}", type)
//                    // use the default string representation
//                    return@runCatching ref.toString()
//                }
//
//                val result = (ref.invokeMethod(
//                    thread,
//                    method,
//                    emptyList(),
//
//                    // INVOKE_SINGLE_THREADED is required to ensure that the thread is suspended
//                    // again after method invocation
//                    ObjectReference.INVOKE_SINGLE_THREADED
//                ) as StringReference).value()
//
//                result
            }

            if (str.isFailure) {
                logger.error(
                    "Failed to get string representation of object {}",
                    ref,
                    str.exceptionOrNull()
                )
            }

            return JavaReferenceValue(ref, str.getOrDefault(ref.toString()))
        }
    }

    override fun toString(): String = stringRepr
}

internal class JavaArrayLikeValue private constructor(
    private val thread: ThreadReference,
    private val jdi: ArrayReference
) : BaseJavaValue(jdi), ArrayLikeValue {

    companion object {
        suspend fun create(thread: ThreadReference, jdi: ArrayReference) =
            JavaArrayLikeValue(thread, jdi)
    }

    override val size: ULong
        get() = jdi.length().toULong()

    override suspend fun get(index: ULong): LspValue =
        toLspValue(thread, jdi.getValue(index.toInt()))
}

internal abstract class AbstractJavaVariable<ValueT : LspValue>(
    protected val thread: ThreadReference,
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
    protected open suspend fun isMutable(): Boolean = VariableValues.canMutate(type)

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
        if (value !is ObjectReference) {
            // TODO: We can provide other information as 'members' for other types of variables.
            return@withContext emptySet()
        }

        val evaluationContext = JavaDebugAdapter.requireInstance()
            .evalContext()
        val type = value.referenceType()
        val fields = evaluationContext.evaluate(thread) { type.allFields() } ?: emptyList()

        fields.map { field ->
            JavaFieldVariable<ValueT>(thread, value, type, field)
        }.toSet()
    }
}

internal class JavaFieldVariable<ValueT : LspValue>(
    thread: ThreadReference,
    private val ref: ObjectReference,
    private val refType: ReferenceType,
    private val field: Field,
) : AbstractJavaVariable<ValueT>(
    thread = thread,
    name = field.name(),
    typeName = field.typeName(),
    type = refType
) {

    companion object {
        private val logger = LoggerFactory.getLogger(JavaFieldVariable::class.java)
    }

    override suspend fun jdiValue(): Value = ref

    @Suppress("UNCHECKED_CAST")
    override suspend fun value(): ValueT {
        val evalContext = JavaDebugAdapter.requireInstance().evalContext()
        return toLspValue(thread, evalContext.evaluate(thread) {
            if (field.isStatic) refType.getValue(field) else ref.getValue(field)
        }!!) as ValueT
    }

    override suspend fun setValue(value: String): Boolean {
        val newValue = VariableValues.parseValue(field, type, value) ?: run {
            logger.error("Failed to parse value '{}' for variable '{}'", value, name)
            return false
        }

        val evalContext = JavaDebugAdapter.requireInstance().evalContext()

        return withContext(Dispatchers.IO) {
            try {
                evalContext.evaluate(thread) {
                    ref.setValue(field, newValue)
                    true
                } ?: false
            } catch (err: Throwable) {
                logger.error("Failed to set value of variable '{}'", name, err)
                false
            }
        }
    }
}

internal open class JavaLocalVariable<ValueType : LspValue>(
    thread: ThreadReference,
    protected val stackFrame: StackFrame,
    protected val variable: LocalVariable,
) : AbstractJavaVariable<ValueType>(
    thread = thread,
    name = variable.name(),
    typeName = variable.typeName(),
    type = if (variable is ObjectReference) variable.referenceType() else variable.type()
) {

    companion object {
        private val logger = LoggerFactory.getLogger(JavaLocalVariable::class.java)
        internal fun forVariable(
            stackFrame: StackFrame,
            variable: LocalVariable,
            thread: ThreadReference = stackFrame.thread(),
        ): JavaLocalVariable<*> = when (variable.type()) {
            is PrimitiveType -> JavaPrimitiveVariable(thread, stackFrame, variable)
            else -> JavaLocalVariable<LspValue>(thread, stackFrame, variable)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun value(): ValueType? {
        if (!stackFrame.thread().isSuspended) {
            logger.warn("Thread is not suspended; cannot access variable '{}'", variable.name())
            return null
        }

        return try {
            val evalContext = JavaDebugAdapter.requireInstance()
                .evalContext()

            val thread = stackFrame.thread()
            val rawValue = evalContext.evaluate(thread) {
                stackFrame.getValue(variable)
            }

            rawValue?.let { value ->
                toLspValue(thread, value) as ValueType
            }
        } catch (e: InternalException) {
            if (e.message?.contains("JDWP Error: 32") == true) {
                logger.warn(
                    "JDWP Error 32: Invalid frame when accessing variable '{}'",
                    variable.name()
                )
            } else {
                logger.error("Unexpected JDWP error when accessing '{}'", variable.name(), e)
            }
            null
        } catch (e: InvalidStackFrameException) {
            logger.warn(
                "Stack frame invalid or no longer available for variable '{}'",
                variable.name()
            )
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

    override suspend fun jdiValue(): Value =
        JavaDebugAdapter.requireInstance().evalContext().evaluate(thread) {
            stackFrame.getValue(variable)
        }!!

    protected inline fun <reified T : Type> requireType(action: () -> Unit) {
        check(type is T) {
            "Variable $name is not of type ${T::class.simpleName}"
        }
        action()
    }

    override suspend fun setValue(value: String): Boolean {
        val newValue = VariableValues.parseValue(variable, type, value) ?: run {
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

    protected suspend fun setValue(value: Value) =
        JavaDebugAdapter.requireInstance().evalContext().evaluate(thread) {
            stackFrame.setValue(variable, value)
        }

    override suspend fun objectMembers(): Set<Variable<*>> {
        return super.objectMembers()
    }
}

internal class JavaPrimitiveVariable(
    thread: ThreadReference,
    stackFrame: StackFrame,
    variable: LocalVariable,
) : JavaLocalVariable<LspPrimitiveValue>(thread, stackFrame, variable), PrimitiveVariable {

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
