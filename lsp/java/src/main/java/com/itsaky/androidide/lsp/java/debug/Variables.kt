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
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.Type
import com.sun.jdi.Value
import com.sun.jdi.VoidValue
import org.slf4j.LoggerFactory
import com.itsaky.androidide.lsp.debug.model.PrimitiveValue as LspPrimitiveValue
import com.itsaky.androidide.lsp.debug.model.ReferenceValue as LspReferenceValue
import com.itsaky.androidide.lsp.debug.model.Value as LspValue

// Must be called in an EvaluationContext
// this method must ensure that it does not start another evaluation in EvaluationContext in order
// to prevent deadlock
private fun toLspValue(
	thread: ThreadReference,
	value: Value,
): LspValue =
	when (value) {
		is PrimitiveValue -> JavaPrimitiveValue.create(value)
		is VoidValue -> JavaVoidValue.create(value)
		is ArrayReference -> JavaArrayLikeValue.create(thread, value)
		is ObjectReference -> JavaReferenceValue.create(thread, value)
		else -> throw IllegalArgumentException("Unsupported value type: $value")
	}

sealed class BaseJavaValue(
	override val value: Any?,
) : LspValue

internal class JavaVoidValue private constructor(
	void: VoidValue,
) : BaseJavaValue(void) {
	companion object {
		fun create(void: VoidValue) = JavaVoidValue(void)
	}

	override fun toString(): String = "void"
}

internal class JavaPrimitiveValue private constructor(
	private val primitive: PrimitiveValue,
) : BaseJavaValue(primitive),
	LspPrimitiveValue {
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
) : BaseJavaValue(ref),
	LspReferenceValue {
	companion object {
		private val logger = LoggerFactory.getLogger(JavaReferenceValue::class.java)

		fun create(
			thread: ThreadReference,
			ref: ObjectReference,
		): JavaReferenceValue {
			val str =
				runCatching {
					if (ref is StringReference) {
						return@runCatching ref.value()
					}

					val type = ref.referenceType()
					val method =
						type
							.methodsByName(
								"toString",
								"()Ljava/lang/String;",
							).firstOrNull() ?: return@runCatching ref.toString()

					val result =
						ref.invokeMethod(
							thread,
							method,
							emptyList(),
							ObjectReference.INVOKE_SINGLE_THREADED,
						)

					(result as? StringReference?)?.value() ?: ref.toString()
				}

			if (str.isFailure) {
				logger.error(
					"Failed to get string representation of object {}",
					ref,
					str.exceptionOrNull(),
				)
			}

			return JavaReferenceValue(ref, str.getOrDefault(ref.toString()))
		}
	}

	override fun toString(): String = stringRepr
}

internal class JavaArrayLikeValue private constructor(
	private val thread: ThreadReference,
	private val jdi: ArrayReference,
) : BaseJavaValue(jdi),
	ArrayLikeValue {
	companion object {
		fun create(
			thread: ThreadReference,
			jdi: ArrayReference,
		) = JavaArrayLikeValue(thread, jdi)
	}

	override val size: ULong
		get() = jdi.length().toULong()

	override suspend fun get(index: ULong): LspValue = toLspValue(thread, jdi.getValue(index.toInt()))
}

internal abstract class AbstractJavaVariable<ValueT : LspValue>(
	protected val thread: ThreadReference,
	protected val name: String,
	protected val typeName: String,
	protected val type: Type,
	protected val value: Value?,
) : Variable<ValueT> {
	companion object {
		private val logger = LoggerFactory.getLogger(AbstractJavaVariable::class.java)
	}

	internal val kind by lazy {
		when (type) {
			is PrimitiveType -> VariableKind.PRIMITIVE

			// TODO: Support other array-like types (like lists).
			is ArrayType -> VariableKind.ARRAYLIKE
			is ObjectReference -> VariableKind.REFERENCE
			else -> VariableKind.UNKNOWN
		}
	}

	protected abstract suspend fun jdiValue(): Value?

	protected open suspend fun isMutable(): Boolean = VariableValues.canMutate(type)

	override suspend fun descriptor(): VariableDescriptor =
		VariableDescriptor(
			name = name,
			typeName = typeName,
			kind = kind,
			isMutable = isMutable(),
		)

	override suspend fun objectMembers(): Set<Variable<*>> {
		val ref = jdiValue()
		if (ref !is ObjectReference) {
			// TODO: We can provide other information as 'members' for other types of variables.
			return emptySet()
		}

		val evaluationContext =
			JavaDebugAdapter
				.requireInstance()
				.evalContext()
		val refType = ref.referenceType()

		return evaluationContext.evaluate(thread) {
			refType
				.allFields()
				.associateWith { field ->
					if (field.isStatic) refType.getValue(field) else ref.getValue(field)
				}.map { (field, value) ->
					JavaFieldVariable<ValueT>(thread, ref, refType, field, value)
				}.toSet()
		} ?: emptySet()
	}
}

internal class ThisVariable<ValueT : LspValue>(
	thread: ThreadReference,
	ref: ObjectReference,
	refType: ReferenceType,
) : AbstractJavaVariable<ValueT>(
		thread = thread,
		name = "this",
		typeName = refType.name(),
		type = refType,
		value = ref,
	) {
	@Suppress("UNCHECKED_CAST")
	override suspend fun value(): ValueT {
		if (this.value == null) {
			return LspValue.UNDEFINED as ValueT
		}

		val evalContext = JavaDebugAdapter.requireInstance().evalContext()
		return evalContext.evaluate(thread) {
			toLspValue(thread, value)
		}!! as ValueT
	}

	override suspend fun jdiValue(): Value? = value

	override suspend fun setValue(value: String): Boolean {
		// 'this' object cannot be modified
		return false
	}
}

internal class JavaFieldVariable<ValueT : LspValue>(
	thread: ThreadReference,
	private val ref: ObjectReference,
	refType: ReferenceType,
	private val field: Field,
	value: Value?,
) : AbstractJavaVariable<ValueT>(
		thread = thread,
		name = field.name(),
		typeName = field.typeName(),
		type = refType,
		value = value,
	) {
	companion object {
		private val logger = LoggerFactory.getLogger(JavaFieldVariable::class.java)
	}

	override suspend fun jdiValue() = value

	@Suppress("UNCHECKED_CAST")
	override suspend fun value(): ValueT {
		if (this.value == null) {
			return LspValue.UNDEFINED as ValueT
		}

		val evalContext = JavaDebugAdapter.requireInstance().evalContext()
		return evalContext.evaluate(thread) {
			toLspValue(thread, value)
		}!! as ValueT
	}

	override suspend fun setValue(value: String): Boolean {
		val newValue =
			VariableValues.parseValue(thread, field, type, value) ?: run {
				logger.error("Failed to parse value '{}' for variable '{}'", value, name)
				return false
			}

		val evalContext = JavaDebugAdapter.requireInstance().evalContext()

		return try {
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

internal open class JavaLocalVariable<ValueType : LspValue>(
	thread: ThreadReference,
	protected val stackFrame: JavaStackFrame,
	protected val variable: LocalVariable,
	value: Value?,
) : AbstractJavaVariable<ValueType>(
		thread = thread,
		name = variable.name(),
		typeName = variable.typeName(),
		type = if (variable is ObjectReference) variable.referenceType() else variable.type(),
		value = value,
	) {
	companion object {
		private val logger = LoggerFactory.getLogger(JavaLocalVariable::class.java)

		internal fun forVariable(
			thread: ThreadReference,
			stackFrame: JavaStackFrame,
			variable: LocalVariable,
			value: Value?,
		): JavaLocalVariable<*> =
			when (variable.type()) {
				is PrimitiveType -> JavaPrimitiveVariable(thread, stackFrame, variable, value)
				else -> JavaLocalVariable<LspValue>(thread, stackFrame, variable, value)
			}
	}

	@Suppress("UNCHECKED_CAST")
	override suspend fun value(): ValueType? {
		if (!thread.isSuspended) {
			logger.warn("Thread is not suspended; cannot access variable '{}'", variable.name())
			return null
		}

		if (this.value == null) {
			return LspValue.UNDEFINED as ValueType
		}

		return try {
			val evalContext =
				JavaDebugAdapter
					.requireInstance()
					.evalContext()

			evalContext.evaluate(thread) {
				toLspValue(thread, value) as ValueType
			}
		} catch (e: InternalException) {
			if (e.message?.contains("JDWP Error: 32") == true) {
				logger.warn(
					"JDWP Error 32: Invalid frame when accessing variable '{}'",
					variable.name(),
				)
			} else {
				logger.error("Unexpected JDWP error when accessing '{}'", variable.name(), e)
			}
			null
		} catch (e: InvalidStackFrameException) {
			logger.warn(
				"Stack frame invalid or no longer available for variable '{}'",
				variable.name(),
			)
			null
		} catch (e: Exception) {
			logger.error(
				"Failed to get value of variable '{}' in {}",
				variable.name(),
				stackFrame.method,
				e,
			)
			null
		}
	}

	override suspend fun jdiValue() = value

	protected inline fun <reified T : Type> requireType(action: () -> Unit) {
		check(type is T) {
			"Variable $name is not of type ${T::class.simpleName}"
		}
		action()
	}

	override suspend fun setValue(value: String): Boolean {
		val newValue =
			VariableValues.parseValue(thread, variable, type, value) ?: run {
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

	protected suspend fun setValue(value: Value): Boolean =
		JavaDebugAdapter.requireInstance().evalContext().evaluate(thread) {
			// At this point, the com.sun.jdi.StackFrame instance we have may have been invalidated
			// due to the thread being resumed for any reason (like when we call toString() on variables)
			// As a result, we need to fetch the frames of the thread, find this frame if it exists,
			// find the variable and then use the newly found frame instance to update the variable
			val newFrame =
				thread.frames().firstOrNull {
					it.location() == stackFrame.location
				}

			if (newFrame == null) {
				logger.error("Unable to update {}, the call stack has been invalidated", variable.name())
				return@evaluate false
			}

			val newVariable = newFrame.visibleVariableByName(variable.name())
			if (newVariable == null) {
				logger.error("Unable to update {}, the variable has been invalidated", variable.name())
				return@evaluate false
			}

			logger.debug("Updating variable {} with value {} in frame {} of thread {}", variable.name(), value, newFrame, thread)
			newFrame.setValue(newVariable, value)
			true
		} ?: false
}

internal class JavaPrimitiveVariable(
	thread: ThreadReference,
	stackFrame: JavaStackFrame,
	variable: LocalVariable,
	value: Value?,
) : JavaLocalVariable<LspPrimitiveValue>(thread, stackFrame, variable, value),
	PrimitiveVariable {
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

	internal suspend fun doSetValue(boolean: Boolean) =
		requireType<ByteType> {
			setValue(variable.mirrorOf(boolean))
		}

	internal suspend fun doSetValue(byte: Byte) =
		requireType<ByteType> {
			setValue(variable.mirrorOf(byte))
		}

	internal suspend fun doSetValue(short: Short) =
		requireType<ByteType> {
			setValue(variable.mirrorOf(short))
		}

	internal suspend fun doSetValue(char: Char) =
		requireType<ByteType> {
			setValue(variable.mirrorOf(char))
		}

	internal suspend fun doSetValue(int: Int) =
		requireType<ByteType> {
			setValue(variable.mirrorOf(int))
		}

	internal suspend fun doSetValue(long: Long) =
		requireType<ByteType> {
			setValue(variable.mirrorOf(long))
		}

	internal suspend fun doSetValue(float: Float) =
		requireType<ByteType> {
			setValue(variable.mirrorOf(float))
		}

	internal suspend fun doSetValue(double: Double) =
		requireType<ByteType> {
			setValue(variable.mirrorOf(double))
		}
}
